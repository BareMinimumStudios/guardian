package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.*
import com.bareminimumstudios.guardian.storage.codec.ContainerChangesCodec
import java.sql.Connection
import java.util.UUID

/** Called only under the backend lock. All multi-table changes share one database transaction. */
internal class JdbcItemRollbackJournal(private val connection: Connection) {
    fun prepare(id: UUID, createdAt: Long, rows: List<ContainerTransactionSnapshot>): ItemRollbackRecord {
        require(createdAt >= 0 && rows.size in 1..50)
        require(rows.sumOf { it.changes.size } <= 2048)
        val owners = rows.flatMap { it.changes.map { change -> change.address.owner } }.distinct()
        require(owners.size <= 32)
        val observed = linkedMapOf<ItemSlotAddress, ItemStackSnapshot>()
        rows.forEach { row -> row.changes.forEach { observed.putIfAbsent(it.address, it.after) } }
        require(ContainerRollbackPlanner.plan(rows, InventorySnapshot(observed)).eligible == rows.size) { "Journal requires an unambiguous conserving transfer chain" }
        val payloads = rows.map { ContainerChangesCodec.encode(it.changes) }
        // Bound the whole plan, not just individual payloads.
        require(payloads.sumOf { it.size.toLong() } <= 16L * 1024 * 1024) { "Journal payload exceeds budget" }
        return atomic {
            val existing = read(id)
            if (existing != null) {
                require(existing.createdAt == createdAt && existing.entries.size == rows.size && existing.entries.zip(rows).all { (a,b) -> a.transactionId == b.transactionId && a.changes == b.changes }) { "Operation ID reused with a different plan" }
                return@atomic existing
            }
            for (row in rows) connection.prepareStatement("SELECT time, interaction, changes FROM ex_container WHERE transaction_uuid = ?").use { query ->
                query.setString(1,row.transactionId.toString())
                query.executeQuery().use { result ->
                    require(result.next()) { "Journal source transaction is not persisted" }
                    require(result.getLong(1) == row.timestampEpochMillis && result.getString(2) == row.action.name && ContainerChangesCodec.decode(result.getBytes(3)) == row.changes) { "Journal source differs from stored history" }
                }
            }
            require(ContainerOwnerIndex.guard(connection,rows).clear) { "Newer history, changed blocks or existing journal claims invalidate the plan" }
            connection.prepareStatement("INSERT INTO ex_item_rollback(operation_uuid, created_at, phase) VALUES (?, ?, ?)").use { insert ->
                insert.setString(1,id.toString());insert.setLong(2,createdAt);insert.setString(3,ItemRollbackPhase.PREPARED.name);insert.executeUpdate()
            }
            owners.forEach { owner -> insertPair("INSERT INTO ex_item_rollback_owner(owner_key, operation_uuid) VALUES (?, ?)",ownerKey(owner),id.toString()) }
            rows.forEachIndexed { index, row ->
                insertPair("INSERT INTO ex_item_rollback_claim(transaction_uuid, operation_uuid) VALUES (?, ?)",row.transactionId.toString(),id.toString())
                connection.prepareStatement("INSERT INTO ex_item_rollback_entry(operation_uuid, entry_index, transaction_uuid, changes) VALUES (?, ?, ?, ?)").use { insert ->
                    insert.setString(1,id.toString());insert.setInt(2,index);insert.setString(3,row.transactionId.toString());insert.setBytes(4,payloads[index]);insert.executeUpdate()
                }
            }
            checkNotNull(read(id))
        }
    }

    fun read(id: UUID): ItemRollbackRecord? {
        val header = connection.prepareStatement("SELECT created_at, phase FROM ex_item_rollback WHERE operation_uuid = ?").use { query ->
            query.setString(1,id.toString());query.executeQuery().use { result -> if (result.next()) result.getLong(1) to ItemRollbackPhase.valueOf(result.getString(2)) else null }
        } ?: return null
        val entries = connection.prepareStatement("SELECT entry_index, transaction_uuid, changes FROM ex_item_rollback_entry WHERE operation_uuid = ? ORDER BY entry_index LIMIT 51").use { query ->
            query.setString(1,id.toString());query.executeQuery().use { result -> buildList {
                var bytes = 0L
                while (result.next()) {
                    require(size < 50 && result.getInt(1) == size) { "Corrupt journal entry order/budget" }
                    val payload=result.getBytes(3);bytes+=payload.size
                    require(bytes <= 16L*1024*1024) { "Corrupt journal payload budget" }
                    add(ItemRollbackEntry(UUID.fromString(result.getString(2)),ContainerChangesCodec.decode(payload)))
                }
            } }
        }
        require(entries.isNotEmpty() && entries.sumOf { it.changes.size } <= 2048) { "Corrupt journal entries" }
        return ItemRollbackRecord(id,header.first,header.second,entries)
    }

    fun protected(id: UUID): Boolean = connection.prepareStatement("SELECT 1 FROM ex_item_rollback_protection WHERE operation_uuid = ?").use { query ->
        query.setString(1,id.toString());query.executeQuery().use { it.next() }
    }

    fun protect(record: ItemRollbackRecord): Boolean {
        require(record.phase == ItemRollbackPhase.PREPARED)
        val owners=ItemRecoveryCheck(record).owners.map(::ownerKey).toSet()
        val sources=record.entries.map { it.transactionId.toString() }.toSet()
        return atomic {
            if (!sameSaveRecord(record,read(record.operationId))) return@atomic false
            val id=record.operationId.toString()
            // Do not silently repair missing/foreign/extra claims or register protection too late.
            if (claimSet("ex_item_rollback_owner","owner_key",id) != owners ||
                claimSet("ex_item_rollback_claim","transaction_uuid",id) != sources) return@atomic false
            if (!protected(record.operationId)) connection.prepareStatement("INSERT INTO ex_item_rollback_protection(operation_uuid) VALUES (?)").use {
                it.setString(1,id);it.executeUpdate()
            }
            true
        }
    }

    private fun claimSet(table: String, column: String, id: String): Set<String> =
        connection.prepareStatement("SELECT $column FROM $table WHERE operation_uuid = ? LIMIT 51").use { query ->
            query.setString(1,id);query.executeQuery().use { result -> buildSet { while(result.next()) add(result.getString(1)) } }
        }

    fun unfinished(limit: Int): List<ItemRollbackSummary> {
        require(limit in 1..100)
        return connection.prepareStatement("SELECT j.operation_uuid, j.created_at, j.phase FROM ex_item_rollback j WHERE j.phase IN ('PREPARED','APPLYING','RECOVERY_REQUIRED') OR EXISTS (SELECT 1 FROM ex_item_rollback_protection p WHERE p.operation_uuid = j.operation_uuid) ORDER BY j.created_at, j.operation_uuid LIMIT ?").use { query ->
            query.setInt(1,limit)
            query.executeQuery().use { result -> buildList {
                while(result.next()) add(ItemRollbackSummary(UUID.fromString(result.getString(1)),result.getLong(2),ItemRollbackPhase.valueOf(result.getString(3))))
            } }
        }
    }

    fun transition(id: UUID, expected: ItemRollbackPhase, next: ItemRollbackPhase): Boolean {
        require(when(expected) {
            ItemRollbackPhase.PREPARED -> next == ItemRollbackPhase.APPLYING || next == ItemRollbackPhase.CANCELLED
            ItemRollbackPhase.APPLYING -> next == ItemRollbackPhase.RECOVERY_REQUIRED || next == ItemRollbackPhase.COMPLETED
            ItemRollbackPhase.RECOVERY_REQUIRED -> next == ItemRollbackPhase.COMPLETED
            else -> false
        }) { "Invalid journal transition" }
        return atomic {
            val held=protected(id)
            // Protected intent cannot be cancelled based on a header alone.
            if (held && next == ItemRollbackPhase.CANCELLED) return@atomic false
            val changed=connection.prepareStatement("UPDATE ex_item_rollback SET phase = ? WHERE operation_uuid = ? AND phase = ?").use { update ->
                update.setString(1,next.name);update.setString(2,id.toString());update.setString(3,expected.name);update.executeUpdate()==1
            }
            if(changed && !held && (next == ItemRollbackPhase.COMPLETED || next == ItemRollbackPhase.CANCELLED)) {
                connection.prepareStatement("DELETE FROM ex_item_rollback_owner WHERE operation_uuid = ?").use { it.setString(1,id.toString());it.executeUpdate() }
                // Completed source claims remain forever: the same history cannot be rolled back twice.
                if(next == ItemRollbackPhase.CANCELLED) connection.prepareStatement("DELETE FROM ex_item_rollback_claim WHERE operation_uuid = ?").use { it.setString(1,id.toString());it.executeUpdate() }
            }
            changed
        }
    }

    private fun insertPair(sql: String, first: String, second: String) = connection.prepareStatement(sql).use { it.setString(1,first);it.setString(2,second);it.executeUpdate() }
    private fun ownerKey(owner: ItemSlotOwner): String {
        require(owner is ItemSlotOwner.BlockContainer || owner is ItemSlotOwner.PlayerInventory)
        return ContainerOwnerIndex.key(owner)
    }
    private fun <T> atomic(action: () -> T): T {
        check(connection.autoCommit)
        connection.autoCommit=false
        try { val value=action();connection.commit();return value }
        catch(error: Throwable) { runCatching { connection.rollback() }.onFailure { error.addSuppressed(it) };throw error }
        finally { connection.autoCommit=true }
    }
}
