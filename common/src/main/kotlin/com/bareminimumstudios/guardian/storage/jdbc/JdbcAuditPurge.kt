package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.storage.purge.*
import java.sql.Connection

/** Backend lock held throughout. History, child indexes and completeness watermark commit together. */
internal class JdbcAuditPurge(private val connection: Connection) {
    private val noJournal = listOf("ex_item_rollback","ex_item_rollback_owner","ex_item_rollback_protection","ex_item_rollback_claim","ex_item_rollback_entry","ex_item_rollback_images").joinToString(" AND ") { "NOT EXISTS (SELECT 1 FROM $it)" }
    private val safeBlock = "b.rolled_back IN (0,1) AND $noJournal AND NOT EXISTS (SELECT 1 FROM ex_block p WHERE p.rolled_back IN (2,3) AND p.wid=b.wid AND p.x=b.x AND p.y=b.y AND p.z=b.z)"
    private data class Source(val kind: PurgeKind, val table: String, val key: String, val predicate: String)
    private val sources get() = listOf(Source(PurgeKind.BLOCK,"ex_block b","b.rowid",safeBlock),Source(PurgeKind.CONTAINER,"ex_container","transaction_uuid",noJournal),Source(PurgeKind.ACTIVITY,"ex_player_activity","event_uuid","1=1"))
    private fun count(table: String, cutoff: Long, predicate: String="1=1"): Long = connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE time < ? AND ($predicate)").use { q ->q.setLong(1,cutoff);q.executeQuery().use { it.next();it.getLong(1) } }
    fun preview(cutoff: Long, limit: Int): AuditPurgePlan {
        require(cutoff>=0 && limit in 1..1000)
        val eligible=PurgeCounts(count("ex_block b",cutoff,safeBlock),count("ex_container",cutoff,noJournal),count("ex_player_activity",cutoff))
        val protected=PurgeCounts(count("ex_block",cutoff)-eligible.blocks,count("ex_container",cutoff)-eligible.containers)
        val candidates=mutableListOf<PurgeCandidate>()
        for(source in sources)connection.prepareStatement("SELECT ${source.key},time FROM ${source.table} WHERE time < ? AND (${source.predicate}) ORDER BY time,${source.key} LIMIT ?").use { q ->
            q.setLong(1,cutoff);q.setInt(2,limit);q.executeQuery().use { result ->while(result.next())candidates.add(PurgeCandidate(source.kind,result.getString(1),result.getLong(2))) }
        }
        return AuditPurgePlan(cutoff,eligible,protected,candidates.sortedWith(compareBy<PurgeCandidate>{it.time}.thenBy{it.kind}.thenBy{it.key}).take(limit))
    }
    fun apply(plan: AuditPurgePlan, permitted: () -> Boolean): AuditPurgeResult {
        check(permitted()) { "Purge authorization withdrawn" }
        connection.autoCommit=false
        try {
            var blocks=0L;var containers=0L;var activity=0L
            for(candidate in plan.candidates) {
                check(permitted()) { "Purge authorization withdrawn" }
                val source=sources.first { it.kind==candidate.kind }
                val allowed=connection.prepareStatement("SELECT 1 FROM ${source.table} WHERE ${source.key} = ? AND time = ? AND time < ? AND (${source.predicate})").use { q ->
                    if(candidate.kind==PurgeKind.BLOCK)q.setLong(1,candidate.key.toLong())else q.setString(1,candidate.key)
                    q.setLong(2,candidate.time);q.setLong(3,plan.cutoff);q.executeQuery().use { it.next() }
                }
                if(!allowed)continue
                if(candidate.kind==PurgeKind.CONTAINER)for(child in listOf("ex_container_item","ex_container_owner","ex_container_location"))connection.prepareStatement("DELETE FROM $child WHERE transaction_uuid = ?").use { q ->q.setString(1,candidate.key);q.executeUpdate() }
                val table=source.table.substringBefore(' ');val key=source.key.substringAfter('.')
                val deleted=connection.prepareStatement("DELETE FROM $table WHERE $key = ?").use { q ->if(candidate.kind==PurgeKind.BLOCK)q.setLong(1,candidate.key.toLong())else q.setString(1,candidate.key);q.executeUpdate() }
                when(candidate.kind){PurgeKind.BLOCK->blocks+=deleted;PurgeKind.CONTAINER->containers+=deleted;PurgeKind.ACTIVITY->activity+=deleted}
            }
            if(blocks+containers>0) {
                val old=connection.prepareStatement("SELECT meta_value FROM ex_meta WHERE meta_key='history_purged_before'").use { q ->q.executeQuery().use { if(it.next())it.getString(1).toLong()else 0L } }
                connection.prepareStatement("DELETE FROM ex_meta WHERE meta_key='history_purged_before'").use { it.executeUpdate() }
                connection.prepareStatement("INSERT INTO ex_meta(meta_key,meta_value) VALUES ('history_purged_before',?)").use { it.setString(1,maxOf(old,plan.cutoff).toString());it.executeUpdate() }
            }
            check(permitted()) { "Purge authorization withdrawn" }
            connection.commit()
            return AuditPurgeResult(PurgeCounts(blocks,containers,activity),plan.candidates.size-(blocks+containers+activity).toInt())
        }catch(error: Throwable){connection.rollback();throw error}finally{connection.autoCommit=true}
    }
}
