package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.storage.purge.*
import java.sql.Connection

/** Backend lock held throughout. History, child indexes and completeness watermark commit together. */
internal class JdbcAuditPurge(private val connection: Connection) {
    private val noJournal = listOf("ex_item_rollback","ex_item_rollback_owner","ex_item_rollback_protection","ex_item_rollback_claim","ex_item_rollback_entry","ex_item_rollback_images").joinToString(" AND ") { "NOT EXISTS (SELECT 1 FROM $it)" }
    private val safeBlock = "b.rolled_back IN (0,1) AND $noJournal AND NOT EXISTS (SELECT 1 FROM ex_block p WHERE p.rolled_back IN (2,3) AND p.wid=b.wid AND p.x=b.x AND p.y=b.y AND p.z=b.z)"
    private data class Source(val kind: PurgeKind, val table: String, val key: String, val predicate: String)
    private val sources get() = listOf(Source(PurgeKind.BLOCK,"ex_block b","b.rowid",safeBlock),Source(PurgeKind.CONTAINER,"ex_container","transaction_uuid",noJournal),Source(PurgeKind.ACTIVITY,"ex_player_activity","event_uuid","1=1"),Source(PurgeKind.ENTITY,"ex_entity","event_uuid","1=1"),Source(PurgeKind.FLUID,"ex_fluid","event_uuid","1=1"))
    private data class Selection(val sql: String, val values: List<String>) {
        fun bind(q: java.sql.PreparedStatement, start: Int) { values.forEachIndexed { i,value -> q.setString(start+i,value) } }
    }
    private fun selection(kind: PurgeKind, scope: PurgeScope): Selection {
        if(scope.global)return Selection("1=1",emptyList())
        if(kind==PurgeKind.ACTIVITY || scope.typed && kind !in setOf(PurgeKind.BLOCK,PurgeKind.CONTAINER))return Selection("1=0",emptyList())
        val clauses=mutableListOf<String>();val values=mutableListOf<String>()
        scope.world?.let { world ->
            clauses += when(kind) {
                PurgeKind.BLOCK -> "EXISTS (SELECT 1 FROM ex_world_map pw WHERE pw.id=b.wid AND pw.world=?)"
                PurgeKind.CONTAINER -> "EXISTS (SELECT 1 FROM ex_container_location pl JOIN ex_world_map pw ON pw.id=pl.wid WHERE pl.transaction_uuid=ex_container.transaction_uuid AND pw.world=?)"
                else -> "dimension=?"
            }
            values += world.toString()
        }
        for((ids,excluded) in listOf(scope.include to false,scope.exclude to true))if(ids.isNotEmpty()) {
            val marks=ids.joinToString(","){"?"}
            val query=if(kind==PurgeKind.BLOCK) "SELECT 1 FROM ex_resource_map pr WHERE pr.id IN (b.before_type,b.after_type) AND pr.resource IN ($marks)"
                else "SELECT 1 FROM ex_container_item pi WHERE pi.transaction_uuid=ex_container.transaction_uuid AND pi.resource IN ($marks)"
            clauses += "${if(excluded) "NOT " else ""}EXISTS ($query)"
            values += ids.map { it.toString() }
        }
        return Selection(clauses.joinToString(" AND ").ifEmpty { "1=1" },values)
    }
    private fun count(source: Source, cutoff: Long, scope: PurgeScope, safe: Boolean=true): Long {
        val filter=selection(source.kind,scope)
        return connection.prepareStatement("SELECT COUNT(*) FROM ${source.table} WHERE time < ? AND (${filter.sql}) AND (${if(safe)source.predicate else "1=1"})").use { q ->
            q.setLong(1,cutoff);filter.bind(q,2);q.executeQuery().use { it.next();it.getLong(1) }
        }
    }
    fun preview(cutoff: Long, limit: Int, scope: PurgeScope = PurgeScope()): AuditPurgePlan {
        require(cutoff>=0 && limit in 1..1000)
        val counts=sources.map { count(it,cutoff,scope) }
        val eligible=PurgeCounts(counts[0],counts[1],counts[2],counts[3],counts[4])
        val protected=PurgeCounts(count(sources[0],cutoff,scope,false)-eligible.blocks,count(sources[1],cutoff,scope,false)-eligible.containers)
        val candidates=mutableListOf<PurgeCandidate>()
        for(source in sources) {
            val filter=selection(source.kind,scope)
            connection.prepareStatement("SELECT ${source.key},time FROM ${source.table} WHERE time < ? AND (${source.predicate}) AND (${filter.sql}) ORDER BY time,${source.key} LIMIT ?").use { q ->
                q.setLong(1,cutoff);filter.bind(q,2);q.setInt(2+filter.values.size,limit)
                q.executeQuery().use { result ->while(result.next())candidates.add(PurgeCandidate(source.kind,result.getString(1),result.getLong(2))) }
            }
        }
        return AuditPurgePlan(cutoff,eligible,protected,candidates.sortedWith(compareBy<PurgeCandidate>{it.time}.thenBy{it.kind}.thenBy{it.key}).take(limit),scope)
    }
    fun apply(plan: AuditPurgePlan, permitted: () -> Boolean): AuditPurgeResult {
        check(permitted()) { "Purge authorization withdrawn" }
        connection.autoCommit=false
        try {
            var blocks=0L;var containers=0L;var activity=0L;var entities=0L;var fluids=0L
            for(candidate in plan.candidates) {
                check(permitted()) { "Purge authorization withdrawn" }
                val source=sources.first { it.kind==candidate.kind }
                val filter=selection(candidate.kind,plan.scope)
                val allowed=connection.prepareStatement("SELECT 1 FROM ${source.table} WHERE ${source.key} = ? AND time = ? AND time < ? AND (${source.predicate}) AND (${filter.sql})").use { q ->
                    if(candidate.kind==PurgeKind.BLOCK)q.setLong(1,candidate.key.toLong())else q.setString(1,candidate.key)
                    q.setLong(2,candidate.time);q.setLong(3,plan.cutoff);filter.bind(q,4);q.executeQuery().use { it.next() }
                }
                if(!allowed)continue
                if(candidate.kind==PurgeKind.CONTAINER)for(child in listOf("ex_container_entity","ex_container_item","ex_container_owner","ex_container_location"))connection.prepareStatement("DELETE FROM $child WHERE transaction_uuid = ?").use { q ->q.setString(1,candidate.key);q.executeUpdate() }
                val table=source.table.substringBefore(' ');val key=source.key.substringAfter('.')
                val deleted=connection.prepareStatement("DELETE FROM $table WHERE $key = ?").use { q ->if(candidate.kind==PurgeKind.BLOCK)q.setLong(1,candidate.key.toLong())else q.setString(1,candidate.key);q.executeUpdate() }
                when(candidate.kind){PurgeKind.BLOCK->blocks+=deleted;PurgeKind.CONTAINER->containers+=deleted;PurgeKind.ACTIVITY->activity+=deleted;PurgeKind.ENTITY->entities+=deleted;PurgeKind.FLUID->fluids+=deleted}
            }
            if(blocks+containers>0) {
                val old=connection.prepareStatement("SELECT meta_value FROM ex_meta WHERE meta_key='history_purged_before'").use { q ->q.executeQuery().use { if(it.next())it.getString(1).toLong()else 0L } }
                connection.prepareStatement("DELETE FROM ex_meta WHERE meta_key='history_purged_before'").use { it.executeUpdate() }
                connection.prepareStatement("INSERT INTO ex_meta(meta_key,meta_value) VALUES ('history_purged_before',?)").use { it.setString(1,maxOf(old,plan.cutoff).toString());it.executeUpdate() }
            }
            check(permitted()) { "Purge authorization withdrawn" }
            connection.commit()
            return AuditPurgeResult(PurgeCounts(blocks,containers,activity,entities,fluids),plan.candidates.size-(blocks+containers+activity+entities+fluids).toInt())
        }catch(error: Throwable){connection.rollback();throw error}finally{connection.autoCommit=true}
    }
}
