package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.checkpoint.*
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/** Invoked under the backend lock. Checkpoint retention never removes recovery authority. */
internal class JdbcInventoryCheckpoints(private val connection: Connection, private val allocator: SequenceAllocator) {
    private fun header(row: ResultSet) = InventoryCheckpointHeader(UUID.fromString(row.getString(1)),UUID.fromString(row.getString(2)),
        row.getString(3),row.getLong(4),row.getString(5),InventoryCheckpointReason.valueOf(row.getString(6)),row.getInt(7))
    private fun header(value: InventoryCheckpoint) = InventoryCheckpointHeader(value.checkpointId,value.playerId,value.playerName,
        value.capturedAt,value.minecraftVersion,value.reason,value.inventory.slots.size)
    private val columns = "checkpoint_uuid,player_uuid,player_name,captured_at,minecraft_version,reason,slot_count"

    fun read(id: UUID): InventoryCheckpoint? = connection.prepareStatement(
        "SELECT $columns,payload_bytes,octet_length(payload),CASE WHEN octet_length(payload)<=? THEN payload ELSE NULL END FROM ex_inventory_checkpoint WHERE checkpoint_uuid=?"
    ).use { query ->
        query.setInt(1,InventoryCheckpointCodec.MAX_BYTES);query.setString(2,id.toString())
        query.executeQuery().use { rows ->
            if(!rows.next()) null else {
                val expected=header(rows);val size=rows.getLong(9)
                require(size in 96..InventoryCheckpointCodec.MAX_BYTES.toLong() && rows.getInt(8).toLong()==size) { "Checkpoint payload size is inconsistent" }
                val payload=checkNotNull(rows.getBytes(10));require(payload.size.toLong()==size)
                InventoryCheckpointCodec.decode(payload).also { require(header(it)==expected) { "Checkpoint header differs from its saved image" } }
            }
        }
    }

    fun list(player: UUID?, limit: Int, offset: Int, after: Long? = null): List<InventoryCheckpointHeader> {
        require(limit in 1..50 && offset in 0..100000 && (after==null || after>=0))
        val clauses=listOfNotNull(if(player!=null)"player_uuid=?" else null,if(after!=null)"captured_at>=?" else null)
        val where=if(clauses.isEmpty())"" else " WHERE "+clauses.joinToString(" AND ")
        return connection.prepareStatement("SELECT $columns FROM ex_inventory_checkpoint"+where+" ORDER BY sequence DESC LIMIT ? OFFSET ?").use {
            var index=1;if(player!=null)it.setString(index++,player.toString());if(after!=null)it.setLong(index++,after)
            it.setInt(index++,limit);it.setInt(index,offset)
            it.executeQuery().use { rows -> buildList { while(rows.next()) add(header(rows)) } }
        }
    }

    fun players(name: String): List<UUID> {
        require(name.length in 1..64 && name.none(Char::isISOControl))
        return connection.prepareStatement("SELECT DISTINCT player_uuid FROM ex_inventory_checkpoint WHERE lower(player_name)=lower(?) ORDER BY player_uuid LIMIT 2").use {
            it.setString(1,name);it.executeQuery().use { rows -> buildList { while(rows.next()) add(UUID.fromString(rows.getString(1))) } }
        }
    }

    fun save(checkpoint: InventoryCheckpoint, limits: InventoryCheckpointLimits): InventoryCheckpointHeader = atomic {
        if(checkpoint.reason==InventoryCheckpointReason.PERIODIC) {
            val latest=list(checkpoint.playerId,1,0).firstOrNull()
            if(latest!=null && latest.minecraftVersion==checkpoint.minecraftVersion && latest.playerName==checkpoint.playerName) {
                val claimed=connection.prepareStatement("SELECT 1 FROM ex_checkpoint_restore_claim WHERE checkpoint_uuid=?").use {
                    it.setString(1,latest.checkpointId.toString());it.executeQuery().use { rows -> rows.next() }
                }
                if(!claimed && read(latest.checkpointId)?.inventory?.slots==checkpoint.inventory.slots)return@atomic latest
            }
        }
        insert(checkpoint)
        trim(checkpoint.playerId,limits,setOf(checkpoint.checkpointId))
        header(checkpoint)
    }

    /** Also used by checkpoint recovery while its link/claim transaction owns the connection. */
    fun insert(checkpoint: InventoryCheckpoint) {
        check(!connection.autoCommit)
        val bytes=InventoryCheckpointCodec.encode(checkpoint)
        read(checkpoint.checkpointId)?.let { existing ->
            require(InventoryCheckpointCodec.encode(existing).contentEquals(bytes)) { "Checkpoint UUID reused with different data" }
            return
        }
        val sequence=allocator.next(connection,"inventory_checkpoint")
        connection.prepareStatement("INSERT INTO ex_inventory_checkpoint(checkpoint_uuid,sequence,player_uuid,player_name,captured_at,minecraft_version,reason,slot_count,payload_bytes,payload) VALUES (?,?,?,?,?,?,?,?,?,?)").use {
            it.setString(1,checkpoint.checkpointId.toString());it.setLong(2,sequence);it.setString(3,checkpoint.playerId.toString())
            it.setString(4,checkpoint.playerName);it.setLong(5,checkpoint.capturedAt);it.setString(6,checkpoint.minecraftVersion)
            it.setString(7,checkpoint.reason.name);it.setInt(8,checkpoint.inventory.slots.size);it.setInt(9,bytes.size);it.setBytes(10,bytes)
            check(it.executeUpdate()==1)
        }
    }

    private val disposable = "NOT EXISTS (SELECT 1 FROM ex_checkpoint_restore_claim c WHERE c.checkpoint_uuid=s.checkpoint_uuid) AND NOT EXISTS (SELECT 1 FROM ex_checkpoint_restore_link l JOIN ex_item_rollback j ON j.operation_uuid=l.operation_uuid WHERE l.before_uuid=s.checkpoint_uuid AND j.phase<>'CANCELLED')"
    private data class Candidate(val id: String, val player: String, val bytes: Long)

    fun trim(player: UUID, limits: InventoryCheckpointLimits, keep: Set<UUID>) {
        check(!connection.autoCommit);require(keep.size in 1..8)
        var count=0L;var bytes=0L
        connection.createStatement().use { it.executeQuery("SELECT COUNT(*),COALESCE(SUM(payload_bytes),0) FROM ex_inventory_checkpoint").use { rows ->
            check(rows.next());count=rows.getLong(1);bytes=rows.getLong(2)
        } }
        var playerCount=connection.prepareStatement("SELECT COUNT(*) FROM ex_inventory_checkpoint s WHERE player_uuid=? AND $disposable").use {
            it.setString(1,player.toString());it.executeQuery().use { rows -> check(rows.next());rows.getLong(1) }
        }
        fun over()=count>limits.total || bytes>limits.bytes || playerCount>limits.perPlayer
        var removed=0
        for(playerOnly in if(playerCount>limits.perPlayer)listOf(true,false) else listOf(false)) {
            if(!over())break
            val placeholders=keep.joinToString(",") { "?" }
            val sql="SELECT checkpoint_uuid,player_uuid,payload_bytes FROM ex_inventory_checkpoint s WHERE $disposable AND checkpoint_uuid NOT IN ($placeholders)"+
                (if(playerOnly)" AND player_uuid=?" else "")+" ORDER BY sequence LIMIT ?"
            val candidates=connection.prepareStatement(sql).use { query ->
                var index=1;keep.forEach { query.setString(index++,it.toString()) };if(playerOnly)query.setString(index++,player.toString());query.setInt(index,1000-removed)
                query.executeQuery().use { rows -> buildList { while(rows.next())add(Candidate(rows.getString(1),rows.getString(2),rows.getLong(3))) } }
            }
            connection.prepareStatement("DELETE FROM ex_inventory_checkpoint WHERE checkpoint_uuid=?").use { delete ->
                for(candidate in candidates) {
                    if(!over() || playerOnly && playerCount<=limits.perPlayer)break
                    delete.setString(1,candidate.id);check(delete.executeUpdate()==1)
                    removed++;count--;bytes-=candidate.bytes
                    if(candidate.player==player.toString())playerCount--
                }
            }
        }
        if(over())throw InventoryCheckpointCapacityException("Checkpoint budget is full or retention requires more than 1,000 deletions; recovery authority was retained")
    }

    private fun <T> atomic(action: () -> T): T {
        check(connection.autoCommit);connection.autoCommit=false
        try { val result=action();connection.commit();return result }
        catch(error: Throwable) { runCatching { connection.rollback() }.onFailure { error.addSuppressed(it) };throw error }
        finally { connection.autoCommit=true }
    }
}
