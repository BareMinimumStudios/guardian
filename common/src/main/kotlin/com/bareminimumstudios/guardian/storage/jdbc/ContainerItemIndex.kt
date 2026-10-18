package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.ItemSlotChange
import com.bareminimumstudios.guardian.storage.codec.ContainerChangesCodec
import java.sql.Connection

internal object ContainerItemIndex {
    fun insert(connection: Connection, id: String, changes: List<ItemSlotChange>) {
        val resources = changes.flatMap { listOfNotNull(it.before.itemId, it.after.itemId) }.toSet()
        connection.prepareStatement("INSERT INTO ex_container_item(transaction_uuid, resource) VALUES (?, ?) ON CONFLICT DO NOTHING").use { s ->
            resources.forEach { s.setString(1,id);s.setString(2,it.toString());s.addBatch() };s.executeBatch()
        }
    }
    /** Keyset batches close the read cursor before writes; preserve old payload bytes unchanged. */
    fun backfill(connection: Connection) {
        var cursor = ""
        while (true) {
            val rows = connection.prepareStatement("SELECT transaction_uuid, changes FROM ex_container WHERE transaction_uuid > ? ORDER BY transaction_uuid LIMIT 128").use { s ->
                s.setString(1,cursor);s.executeQuery().use { r -> buildList { while(r.next()) add(r.getString(1) to ContainerChangesCodec.decode(r.getBytes(2))) } }
            }
            if(rows.isEmpty()) return
            rows.forEach { (id,changes) -> insert(connection,id,changes) };cursor = rows.last().first
        }
    }
}
