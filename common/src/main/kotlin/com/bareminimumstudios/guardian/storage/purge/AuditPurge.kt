package com.bareminimumstudios.guardian.storage.purge

import java.util.Collections
import java.util.UUID

enum class PurgeKind { BLOCK, CONTAINER, ACTIVITY, ENTITY, FLUID }
data class PurgeCandidate(val kind: PurgeKind, val key: String, val time: Long)
data class PurgeCounts @JvmOverloads constructor(val blocks: Long = 0, val containers: Long = 0, val activity: Long = 0, val entities: Long = 0, val fluids: Long = 0) {
    init { require(blocks >= 0 && containers >= 0 && activity >= 0 && entities >= 0 && fluids >= 0) }
    val total: Long get() = blocks + containers + activity + entities + fluids
}
/** Fixed immutable keys; later inserts cannot enter the confirmed batch. */
class AuditPurgePlan(val cutoff: Long, val eligible: PurgeCounts, val protected: PurgeCounts, candidates: List<PurgeCandidate>) {
    val candidates: List<PurgeCandidate> = Collections.unmodifiableList(ArrayList(candidates))
    init {
        require(cutoff >= 0 && candidates.size <= 1000)
        require(candidates.all { it.time >= 0 && it.time < cutoff })
        require(candidates.map { it.kind to it.key }.distinct().size == candidates.size)
    }
}
data class AuditPurgeResult(val deleted: PurgeCounts, val skipped: Int)

/** One requester-bound, single-use confirmation, valid for sixty seconds. */
class PurgeTickets(private val clock: () -> Long = System::nanoTime) {
    data class Ticket(val token: UUID, val plan: AuditPurgePlan)
    private var pending: Triple<String, Ticket, Long>? = null
    fun offer(owner: String, plan: AuditPurgePlan): Ticket = Ticket(UUID.randomUUID(), plan).also { pending = Triple(owner, it, clock()) }
    fun peek(owner: String): Ticket? {
        val entry = pending ?: return null
        if(clock()-entry.third >= 60_000_000_000L) { pending=null;return null }
        return entry.second.takeIf { entry.first == owner }
    }
    fun take(owner: String, token: UUID): AuditPurgePlan? {
        val ticket=peek(owner)?.takeIf { it.token==token } ?: return null
        pending=null
        return ticket.plan
    }
    fun cancel(owner: String): Boolean = (pending?.first == owner).also { if(it) pending=null }
    fun clear() { pending=null }
}
