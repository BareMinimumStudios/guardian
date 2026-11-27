package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.checkpoint.CheckpointRequester
import com.bareminimumstudios.guardian.checkpoint.CheckpointRestoreLink
import com.bareminimumstudios.guardian.checkpoint.CheckpointRestoreRefusedException
import com.bareminimumstudios.guardian.checkpoint.CheckpointRestoreSource
import com.bareminimumstudios.guardian.checkpoint.InventoryCheckpoint
import com.bareminimumstudios.guardian.checkpoint.InventoryCheckpointCodec
import com.bareminimumstudios.guardian.checkpoint.InventoryCheckpointLimits
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.*
import com.bareminimumstudios.guardian.storage.codec.ContainerChangesCodec
import com.bareminimumstudios.guardian.storage.codec.ItemRollbackImagesCodec
import java.sql.Connection
import java.util.UUID

/** Called only under the backend lock. All multi-table changes share one database transaction. */
internal class JdbcItemRollbackJournal(private val connection: Connection) {
    fun prepare(id: UUID, createdAt: Long, rows: List<ContainerTransactionSnapshot>, purpose: ItemRecoveryPurpose = ItemRecoveryPurpose.TRANSFER_ROLLBACK): ItemRollbackRecord {
        require(purpose == ItemRecoveryPurpose.TRANSFER_ROLLBACK || purpose == ItemRecoveryPurpose.CREATIVE_DELETION) { "Recovery must use its linked authority entry point" }
        require(createdAt >= 0 && rows.size in 1..50)
        require(rows.sumOf { it.changes.size } <= 2048)
        val owners = rows.flatMap { it.changes.map { change -> change.address.owner } }.distinct()
        require(owners.size <= 32)
        val observed = linkedMapOf<ItemSlotAddress, ItemStackSnapshot>()
        rows.forEach { row -> row.changes.forEach { observed.putIfAbsent(it.address, it.after) } }
        require(ContainerRollbackPlanner.plan(rows, InventorySnapshot(observed),purpose=purpose).eligible == rows.size) { "Journal requires a valid, unambiguous plan for the selected recovery purpose" }
        ItemRecoveryPolicy.validate(purpose,rows.map { ItemRollbackEntry(it.transactionId,it.changes) })
        require(rows.all { ItemRecoveryPolicy.transaction(purpose,it) })
        val payloads = rows.map { ContainerChangesCodec.encode(it.changes) }
        // Bound the whole plan, not just individual payloads.
        require(payloads.sumOf { it.size.toLong() } <= 16L * 1024 * 1024) { "Journal payload exceeds budget" }
        return atomic {
            val existing = read(id)
            if (existing != null) {
                require(existing.purpose == purpose && existing.createdAt == createdAt && existing.entries.size == rows.size && existing.entries.zip(rows).all { (a,b) -> a.transactionId == b.transactionId && a.changes == b.changes }) { "Operation ID reused with a different plan" }
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
            connection.prepareStatement("INSERT INTO ex_item_rollback(operation_uuid, created_at, phase, purpose) VALUES (?, ?, ?, ?)").use { insert ->
                insert.setString(1,id.toString());insert.setLong(2,createdAt);insert.setString(3,ItemRollbackPhase.PREPARED.name);insert.setString(4,purpose.name);insert.executeUpdate()
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

    /** Reads immutable authority and all current history guards without reserving anything. */
    fun restorable(limit: Int): List<ItemRollbackSummary> {
        require(limit in 1..20)
        return connection.prepareStatement("SELECT j.operation_uuid,j.created_at,j.phase FROM ex_item_rollback j JOIN ex_item_rollback_images i ON i.operation_uuid=j.operation_uuid WHERE j.phase='COMPLETED' AND j.purpose='TRANSFER_ROLLBACK' AND i.acknowledged=1 AND NOT EXISTS (SELECT 1 FROM ex_item_restore_claim c WHERE c.source_operation_uuid=j.operation_uuid) ORDER BY j.created_at DESC,j.operation_uuid DESC LIMIT ?").use { query ->
            query.setInt(1,limit);query.executeQuery().use { result -> buildList {
                while(result.next()) add(ItemRollbackSummary(UUID.fromString(result.getString(1)),result.getLong(2),ItemRollbackPhase.COMPLETED))
            } }
        }
    }

    fun restoreSource(id: UUID): ItemRestoreSource {
        val parent = read(id) ?: throw ItemRestoreRefusedException("Original item rollback was not found")
        if(parent.phase != ItemRollbackPhase.COMPLETED || parent.purpose != ItemRecoveryPurpose.TRANSFER_ROLLBACK)
            throw ItemRestoreRefusedException("Only a completed item-transfer rollback can be redone")
        val receipt = images(id) ?: throw ItemRestoreRefusedException("Original saved receipt is unavailable")
        if(!receipt.acknowledged) throw ItemRestoreRefusedException("Original item rollback is not durably complete")
        if(restoreClaim(id) != null) throw ItemRestoreRefusedException("This item rollback already has a redo operation")
        val sources = parent.entries.map { entry ->
            connection.prepareStatement("SELECT time, interaction, changes FROM ex_container WHERE transaction_uuid=?").use { query ->
                query.setString(1, entry.transactionId.toString())
                query.executeQuery().use { result ->
                    if(!result.next()) throw ItemRestoreRefusedException("Original item history is no longer available")
                    require(ItemRecoveryPolicy.transferAction(ContainerAction.valueOf(result.getString(2))))
                    require(ContainerChangesCodec.decode(result.getBytes(3)) == entry.changes) { "Original item history differs" }
                    ContainerOwnerIndex.Source(entry.transactionId, result.getLong(1), entry.changes)
                }
            }
        }
        val guard = ContainerOwnerIndex.guardSources(connection, sources)
        // images() verified that these exact permanent source claims belong to this parent.
        require(guard.claimedTransactions == parent.entries.map { it.transactionId }.toSet()) { "Original source claims differ" }
        return ItemRestoreSource(receipt.images, ContainerHistoryGuard(guard.newerOwners, guard.changedBlocks,
            guard.reservedOwners, emptySet(), guard.incompleteTransactions))
    }

    fun prepareRestore(id: UUID, time: Long, parentId: UUID): ItemRollbackRecord = atomic {
        val existing = read(id)
        if (existing != null) {
            require(existing.purpose == ItemRecoveryPurpose.TRANSFER_REDO && existing.redoOf == parentId && existing.createdAt == time)
            require(hasSourceClaims(existing)) { "Existing redo authority differs" }
            return@atomic existing
        }
        val source = restoreSource(parentId)
        if(!source.guard.clear) throw ItemRestoreRefusedException("Newer history, changed blocks or reserved inventories prevent redo")
        val record = source.record(id, time)
        ItemRollbackImages(record, source.images.expected).also {
            require(it.expected.slots == source.images.original.slots) { "Inverse does not reproduce the saved original image" }
        }
        connection.prepareStatement("INSERT INTO ex_item_rollback(operation_uuid,created_at,phase,purpose) VALUES (?,?,'PREPARED',?)").use {
            it.setString(1,id.toString());it.setLong(2,time);it.setString(3,record.purpose.name);it.executeUpdate()
        }
        insertPair("INSERT INTO ex_item_restore_link(operation_uuid,source_operation_uuid) VALUES (?,?)",id.toString(),parentId.toString())
        insertPair("INSERT INTO ex_item_restore_claim(source_operation_uuid,operation_uuid) VALUES (?,?)",parentId.toString(),id.toString())
        ItemRecoveryCheck(record).owners.forEach { owner ->
            insertPair("INSERT INTO ex_item_rollback_owner(owner_key,operation_uuid) VALUES (?,?)",ownerKey(owner),id.toString())
        }
        record.entries.forEachIndexed { index, entry ->
            connection.prepareStatement("INSERT INTO ex_item_rollback_entry(operation_uuid,entry_index,transaction_uuid,changes) VALUES (?,?,?,?)").use {
                it.setString(1,id.toString());it.setInt(2,index);it.setString(3,entry.transactionId.toString())
                it.setBytes(4,ContainerChangesCodec.encode(entry.changes));it.executeUpdate()
            }
        }
        checkNotNull(read(id))
    }

    fun checkpointSource(id: UUID): CheckpointRestoreSource {
        val saved=checkpointStore().read(id) ?: throw CheckpointRestoreRefusedException("Checkpoint was not found or has expired")
        if(checkpointClaim(id)!=null)throw CheckpointRestoreRefusedException("This checkpoint already has a restore operation")
        return CheckpointRestoreSource(saved)
    }

    fun checkpointLink(id: UUID): CheckpointRestoreLink? =
        connection.prepareStatement("SELECT source_uuid,before_uuid,requester_uuid,requester_name FROM ex_checkpoint_restore_link WHERE operation_uuid=?").use {
            it.setString(1,id.toString());it.executeQuery().use { row -> if(!row.next())null else
                CheckpointRestoreLink(UUID.fromString(row.getString(1)),UUID.fromString(row.getString(2)),
                    CheckpointRequester(row.getString(3)?.let(UUID::fromString),row.getString(4)))
            }
        }
    private fun checkpointStore() = JdbcInventoryCheckpoints(connection,SequenceAllocator())
    private fun checkpointClaim(id:UUID):UUID? = connection.prepareStatement("SELECT operation_uuid FROM ex_checkpoint_restore_claim WHERE checkpoint_uuid=?").use {
        it.setString(1,id.toString());it.executeQuery().use { row -> if(row.next())UUID.fromString(row.getString(1)) else null }
    }
    private fun checkpointClaims(record: ItemRollbackRecord,image:ItemRollbackImages?):Boolean {
        val link=checkpointLink(record.operationId) ?: return false
        if(link.checkpoint!=record.checkpointOf || checkpointClaim(link.checkpoint)!=record.operationId ||
            claimSet("ex_item_rollback_claim","transaction_uuid",record.operationId.toString()).isNotEmpty() ||
            claimSet("ex_item_restore_claim","source_operation_uuid",record.operationId.toString()).isNotEmpty())return false
        val saved=checkpointStore().read(link.checkpoint) ?: return false
        val before=checkpointStore().read(link.before) ?: return false
        val canonical=CheckpointRestoreSource(saved).record(record.operationId,record.createdAt,before)
        return sameSaveRecord(canonical,record,record.phase) && (image==null ||
            image.original.slots==before.fullInventory.slots && image.expected.slots==saved.fullInventory.slots)
    }

    fun prepareCheckpoint(id:UUID,time:Long,checkpointId:UUID,before:InventoryCheckpoint,
        requester:CheckpointRequester,limits:InventoryCheckpointLimits):ItemRollbackRecord = atomic {
        val existing=read(id)
        if(existing!=null) {
            require(existing.purpose==ItemRecoveryPurpose.CHECKPOINT_RESTORE && existing.checkpointOf==checkpointId && existing.createdAt==time)
            val link=checkNotNull(checkpointLink(id));require(link.before==before.checkpointId && link.requester==requester)
            val stored=checkNotNull(checkpointStore().read(link.before))
            require(InventoryCheckpointCodec.encode(stored).contentEquals(InventoryCheckpointCodec.encode(before)))
            require(hasSourceClaims(existing));return@atomic existing
        }
        val source=checkpointSource(checkpointId)
        val record=source.record(id,time,before)
        val owners=ItemRecoveryCheck(record).owners
        for(owner in owners) connection.prepareStatement("SELECT 1 FROM ex_item_rollback_owner WHERE owner_key=?").use {
            it.setString(1,ownerKey(owner));it.executeQuery().use { row -> if(row.next())
                throw CheckpointRestoreRefusedException("Player inventory is reserved by another recovery operation") }
        }
        connection.prepareStatement("INSERT INTO ex_item_rollback(operation_uuid,created_at,phase,purpose) VALUES (?,?,'PREPARED',?)").use {
            it.setString(1,id.toString());it.setLong(2,time);it.setString(3,record.purpose.name);it.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO ex_checkpoint_restore_link(operation_uuid,source_uuid,before_uuid,requester_uuid,requester_name) VALUES (?,?,?,?,?)").use {
            it.setString(1,id.toString());it.setString(2,checkpointId.toString());it.setString(3,before.checkpointId.toString());it.setString(4,requester.uuid?.toString());it.setString(5,requester.name);it.executeUpdate()
        }
        insertPair("INSERT INTO ex_checkpoint_restore_claim(checkpoint_uuid,operation_uuid) VALUES (?,?)",checkpointId.toString(),id.toString())
        owners.forEach { insertPair("INSERT INTO ex_item_rollback_owner(owner_key,operation_uuid) VALUES (?,?)",ownerKey(it),id.toString()) }
        checkpointStore().insert(before)
        checkpointStore().trim(before.playerId,limits,setOf(checkpointId,before.checkpointId))
        val entry=record.entries.single()
        connection.prepareStatement("INSERT INTO ex_item_rollback_entry(operation_uuid,entry_index,transaction_uuid,changes) VALUES (?,0,?,?)").use {
            it.setString(1,id.toString());it.setString(2,entry.transactionId.toString());it.setBytes(3,ContainerChangesCodec.encode(entry.changes));it.executeUpdate()
        }
        checkNotNull(read(id))
    }


    private fun restoreClaim(parent: UUID): UUID? = connection.prepareStatement("SELECT operation_uuid FROM ex_item_restore_claim WHERE source_operation_uuid=?").use {
        it.setString(1,parent.toString());it.executeQuery().use { result -> if(result.next()) UUID.fromString(result.getString(1)) else null }
    }

    private fun hasSourceClaims(record: ItemRollbackRecord, image: ItemRollbackImages? = null): Boolean {
        if(record.purpose==ItemRecoveryPurpose.CHECKPOINT_RESTORE)return checkpointClaims(record,image)
        val id = record.operationId.toString()
        val sources = record.entries.map { it.transactionId.toString() }.toSet()
        if(record.purpose != ItemRecoveryPurpose.TRANSFER_REDO)
            return claimSet("ex_item_rollback_claim","transaction_uuid",id) == sources
        val parentId = record.redoOf ?: return false
        if(restoreClaim(parentId) != record.operationId || claimSet("ex_item_rollback_claim","transaction_uuid",id).isNotEmpty()) return false
        val parent = read(parentId) ?: return false
        if(parent.phase != ItemRollbackPhase.COMPLETED || parent.purpose != ItemRecoveryPurpose.TRANSFER_ROLLBACK) return false
        val receipt = images(parentId) ?: return false
        if(!receipt.acknowledged) return false
        val canonical = ItemRestoreSource(receipt.images,ContainerHistoryGuard()).record(record.operationId,record.createdAt)
        return sameSaveRecord(canonical,record,record.phase) && (image == null ||
            image.original.slots == receipt.images.expected.slots && image.expected.slots == receipt.images.original.slots)
    }


    fun read(id: UUID): ItemRollbackRecord? {
        val header = connection.prepareStatement("SELECT created_at, phase, purpose FROM ex_item_rollback WHERE operation_uuid = ?").use { query ->
            query.setString(1,id.toString());query.executeQuery().use { result -> if (result.next()) Triple(result.getLong(1),ItemRollbackPhase.valueOf(result.getString(2)),ItemRecoveryPurpose.valueOf(result.getString(3))) else null }
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
        val parents = claimSet("ex_item_restore_link","source_operation_uuid",id.toString())
        require(parents.size == if(header.third==ItemRecoveryPurpose.TRANSFER_REDO) 1 else 0) { "Redo parent link is inconsistent" }
        val checkpoints=claimSet("ex_checkpoint_restore_link","source_uuid",id.toString())
        require(checkpoints.size==if(header.third==ItemRecoveryPurpose.CHECKPOINT_RESTORE)1 else 0) { "Checkpoint source link is inconsistent" }
        return ItemRollbackRecord(id,header.first,header.second,entries,header.third,parents.singleOrNull()?.let(UUID::fromString),checkpoints.singleOrNull()?.let(UUID::fromString))
    }

    fun protected(id: UUID): Boolean = connection.prepareStatement("SELECT 1 FROM ex_item_rollback_protection WHERE operation_uuid = ?").use { query ->
        query.setString(1,id.toString());query.executeQuery().use { it.next() }
    }

    fun protect(record: ItemRollbackRecord): Boolean {
        require(record.phase == ItemRollbackPhase.PREPARED)
        val owners=ItemRecoveryCheck(record).owners.map(::ownerKey).toSet()
        return atomic {
            if (!sameSaveRecord(record,read(record.operationId))) return@atomic false
            val id=record.operationId.toString()
            // Do not silently repair missing/foreign/extra claims or register protection too late.
            if (claimSet("ex_item_rollback_owner","owner_key",id) != owners ||
                !hasSourceClaims(record)) return@atomic false
            if (!protected(record.operationId)) connection.prepareStatement("INSERT INTO ex_item_rollback_protection(operation_uuid) VALUES (?)").use {
                it.setString(1,id);it.executeUpdate()
            }
            true
        }
    }

    fun images(id: UUID): ItemRollbackImageRecord? {
        val record=read(id) ?: return null
        return connection.prepareStatement("SELECT payload, acknowledged FROM ex_item_rollback_images WHERE operation_uuid = ?").use { query ->
            query.setString(1,id.toString());query.executeQuery().use { result ->
                if(!result.next()) null else {
                    val flag=result.getInt(2);require(flag==0 || flag==1)
                    val images=ItemRollbackImagesCodec.decode(record,result.getBytes(1))
                                require(hasSourceClaims(record,images)) { "Receipt source claims or saved redo image differ" }
                    if(flag==1) require(record.phase==ItemRollbackPhase.COMPLETED && !protected(id) &&
                        claimSet("ex_item_rollback_owner","owner_key",id.toString()).isEmpty()) { "Durable decision is inconsistent" }
                    else require(protected(id) && claimSet("ex_item_rollback_owner","owner_key",id.toString())==
                        ItemRecoveryCheck(record).owners.map(::ownerKey).toSet()) { "Unresolved receipt claims differ" }
                    ItemRollbackImageRecord(images,flag==1)
                }
            }
        }
    }

    fun protect(record: ItemRollbackRecord, original: InventorySnapshot): Boolean {
        require(record.phase==ItemRollbackPhase.PREPARED)
        val image=ItemRollbackImages(record,original)
        val payload=ItemRollbackImagesCodec.encode(image)
        return atomic {
            if(!sameSaveRecord(record,read(record.operationId))) return@atomic false
            val id=record.operationId.toString()
            if(claimSet("ex_item_rollback_owner","owner_key",id)!=ItemRecoveryCheck(record).owners.map(::ownerKey).toSet() ||
                !hasSourceClaims(record,image)) return@atomic false
            val existing=images(record.operationId)
            if(existing!=null && (existing.acknowledged || !sameRollbackImages(image,existing.images))) return@atomic false
            if(!protected(record.operationId)) connection.prepareStatement("INSERT INTO ex_item_rollback_protection(operation_uuid) VALUES (?)").use { it.setString(1,id);it.executeUpdate() }
            if(existing==null) connection.prepareStatement("INSERT INTO ex_item_rollback_images(operation_uuid, payload, acknowledged) VALUES (?, ?, 0)").use {
                it.setString(1,id);it.setBytes(2,payload);it.executeUpdate()
            }
            true
        }
    }

    fun cancelUnstarted(record: ItemRollbackRecord): Boolean {
        require(record.phase==ItemRollbackPhase.PREPARED)
        return atomic {
            val receipt=images(record.operationId) ?: return@atomic false
            val id=record.operationId.toString()
            if(receipt.acknowledged || !sameSaveRecord(record,read(record.operationId)) || !protected(record.operationId) ||
                claimSet("ex_item_rollback_owner","owner_key",id)!=ItemRecoveryCheck(record).owners.map(::ownerKey).toSet())return@atomic false
            connection.prepareStatement("UPDATE ex_item_rollback SET phase='CANCELLED' WHERE operation_uuid=? AND phase='PREPARED'").use { it.setString(1,id);check(it.executeUpdate()==1) }
            for(table in listOf("ex_item_rollback_images","ex_item_rollback_protection","ex_item_rollback_owner","ex_item_rollback_claim","ex_item_restore_claim","ex_checkpoint_restore_claim")) {
                connection.prepareStatement("DELETE FROM $table WHERE operation_uuid=?").use { it.setString(1,id);it.executeUpdate() }
            }
            true
        }
    }

    fun acknowledge(record: ItemRollbackRecord, expected: ItemRollbackImages, saved: InventorySnapshot): Boolean {
        require(record.phase==ItemRollbackPhase.COMPLETED || record.phase==ItemRollbackPhase.RECOVERY_REQUIRED)
        ItemRecoveryCheck(record)
        if(!sameSaveRecord(record,expected.record,expected.record.phase) || saved.slots!=expected.expected.slots) return false
        return atomic {
            val actual=read(record.operationId) ?: return@atomic false
            val receipt=images(record.operationId) ?: return@atomic false
            if(!sameRollbackImages(expected,receipt.images)) return@atomic false
            val id=record.operationId.toString()
            if(!hasSourceClaims(record)) return@atomic false
            if(receipt.acknowledged) {
                // A durable prior decision is retried, never re-applied. Later owners may have new claims.
                return@atomic actual.phase==ItemRollbackPhase.COMPLETED && sameSaveRecord(record,actual,ItemRollbackPhase.COMPLETED) &&
                    !protected(record.operationId) && claimSet("ex_item_rollback_owner","owner_key",id).isEmpty()
            }
            if(!sameSaveRecord(record,actual) || !protected(record.operationId) ||
                claimSet("ex_item_rollback_owner","owner_key",id)!=ItemRecoveryCheck(record).owners.map(::ownerKey).toSet()) return@atomic false
            if(record.phase==ItemRollbackPhase.RECOVERY_REQUIRED) connection.prepareStatement("UPDATE ex_item_rollback SET phase = 'COMPLETED' WHERE operation_uuid = ? AND phase = 'RECOVERY_REQUIRED'").use {
                it.setString(1,id);check(it.executeUpdate()==1)
            }
            connection.prepareStatement("UPDATE ex_item_rollback_images SET acknowledged = 1 WHERE operation_uuid = ? AND acknowledged = 0").use {it.setString(1,id);check(it.executeUpdate()==1)}
            connection.prepareStatement("DELETE FROM ex_item_rollback_owner WHERE operation_uuid = ?").use {it.setString(1,id);it.executeUpdate()}
            connection.prepareStatement("DELETE FROM ex_item_rollback_protection WHERE operation_uuid = ?").use {it.setString(1,id);check(it.executeUpdate()==1)}
            // Retain the source claims and complete images as a durable idempotent decision receipt.
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
                if(next == ItemRollbackPhase.CANCELLED) for(table in listOf("ex_item_rollback_claim","ex_item_restore_claim","ex_checkpoint_restore_claim")) connection.prepareStatement("DELETE FROM $table WHERE operation_uuid = ?").use { it.setString(1,id.toString());it.executeUpdate() }
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
