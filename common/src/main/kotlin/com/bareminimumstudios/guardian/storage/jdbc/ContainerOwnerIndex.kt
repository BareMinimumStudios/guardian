package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.ContainerHistoryGuard
import com.bareminimumstudios.guardian.storage.codec.ContainerChangesCodec
import java.sql.Connection

/** Persistent owner index includes temporary player-owned slots, which can block unsafe history gaps. */
internal object ContainerOwnerIndex {
    fun key(owner: ItemSlotOwner): String = when(owner) {
        is ItemSlotOwner.BlockContainer -> "b:${owner.dimension}:${owner.position.x}:${owner.position.y}:${owner.position.z}"
        is ItemSlotOwner.PlayerInventory -> "p:${owner.playerId}"
        is ItemSlotOwner.Cursor -> "p:${owner.playerId}"
        is ItemSlotOwner.CraftingGrid -> "p:${owner.playerId}"
    }

    fun insert(connection: Connection, id: String, time: Long, keys: Set<String>) {
        connection.prepareStatement("INSERT INTO ex_container_owner(transaction_uuid, owner_key, time) VALUES (?, ?, ?)").use { statement ->
            keys.forEach { key -> statement.setString(1,id);statement.setString(2,key);statement.setLong(3,time);statement.addBatch() }
            statement.executeBatch()
        }
    }

    /** Keyset pages retain only keys. Decode one bounded payload at a time, within the migration transaction. */
    fun backfill(connection: Connection) {
        var cursor=""
        while(true) {
            val batch=connection.prepareStatement("SELECT transaction_uuid, time, changes FROM ex_container WHERE transaction_uuid > ? ORDER BY transaction_uuid LIMIT 128").use { statement ->
                statement.setString(1,cursor)
                statement.executeQuery().use { result -> buildList {
                    while(result.next()) add(Triple(result.getString(1),result.getLong(2),ContainerChangesCodec.decode(result.getBytes(3)).map { key(it.address.owner) }.toSet()))
                } }
            }
            if(batch.isEmpty()) return
            batch.forEach { (id,time,keys) -> insert(connection,id,time,keys) }
            cursor=batch.last().first
        }
    }

    fun guard(connection: Connection, rows: List<ContainerTransactionSnapshot>): ContainerHistoryGuard {
        require(rows.size <= 50 && rows.sumOf { it.changes.size } <= 2048)
        if(rows.isEmpty()) return ContainerHistoryGuard()
        val selected=rows.map { it.transactionId.toString() }.toSet()
        require(selected.size==rows.size)
        val cutoffs=linkedMapOf<ItemSlotOwner,Long>()
        rows.forEach { row -> row.changes.forEach { change -> cutoffs.merge(change.address.owner,row.timestampEpochMillis,::minOf) } }
        require(cutoffs.size <= 32)
        val newer=mutableSetOf<ItemSlotOwner>();val blocks=mutableSetOf<ItemSlotOwner>();val reserved=mutableSetOf<ItemSlotOwner>()
        val placeholders=selected.joinToString(",") { "?" }
        for((owner,time) in cutoffs) {
            connection.prepareStatement("SELECT 1 FROM ex_container_owner WHERE owner_key = ? AND time >= ? AND transaction_uuid NOT IN ($placeholders) LIMIT 1").use { query ->
                query.setString(1,key(owner));query.setLong(2,time);selected.forEachIndexed { index,id -> query.setString(index+3,id) }
                query.executeQuery().use { if(it.next()) newer.add(owner) }
            }
            connection.prepareStatement("SELECT 1 FROM ex_item_rollback_owner WHERE owner_key = ? LIMIT 1").use { query ->
                query.setString(1,key(owner));query.executeQuery().use { if(it.next()) reserved.add(owner) }
            }
            if(owner is ItemSlotOwner.BlockContainer) connection.prepareStatement("SELECT 1 FROM ex_block b JOIN ex_world_map w ON w.id=b.wid WHERE w.world = ? AND b.x = ? AND b.y = ? AND b.z = ? AND b.time >= ? LIMIT 1").use { query ->
                query.setString(1,owner.dimension.toString());query.setInt(2,owner.position.x);query.setInt(3,owner.position.y);query.setInt(4,owner.position.z);query.setLong(5,time)
                query.executeQuery().use { if(it.next()) blocks.add(owner) }
            }
        }
        val claimed=mutableSetOf<java.util.UUID>()
        connection.prepareStatement("SELECT transaction_uuid FROM ex_item_rollback_claim WHERE transaction_uuid IN ($placeholders)").use { query ->
            selected.forEachIndexed { index,id -> query.setString(index+1,id) };query.executeQuery().use { result -> while(result.next()) claimed.add(java.util.UUID.fromString(result.getString(1))) }
        }
        return ContainerHistoryGuard(newer,blocks,reserved,claimed)
    }
}
