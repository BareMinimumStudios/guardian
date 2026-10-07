package com.bareminimumstudios.guardian.logging.bulk

import com.bareminimumstudios.guardian.domain.LogEntry

/**
 * Operation-local bulk capture reservation.
 *
 * Successful audit entries are streamed to [BulkAuditDispatcher] in bounded chunks while the external operation is
 * still running. The reservation itself is retained until [commit] so the configured pending-operation limit remains
 * meaningful for the complete WorldEdit edit rather than for individual chunks.
 *
 * All methods are synchronized because integrations are not required to call the extent from the same thread forever.
 */
class BulkCaptureSession internal constructor(
    private val owner: BulkAuditDispatcher,
    val source: String,
    private val maxEntries: Int,
    private val chunkSize: Int
) {
    init {
        require(maxEntries > 0)
        require(chunkSize in 1..maxEntries)
    }

    private val pending = ArrayList<LogEntry>(minOf(chunkSize, 4_096))
    private var totalEntries = 0
    private var finished = false

    val size: Int
        @Synchronized get() = totalEntries

    @Synchronized
    fun hasCapacity(): Boolean = !finished && totalEntries < maxEntries && owner.accepting()

    /**
     * Record a successful mutation. Chunks are transferred immediately once [chunkSize] is reached, bounding the
     * operation-local memory footprint even for very large edits.
     */
    @Synchronized
    fun record(entry: LogEntry) {
        check(!finished) { "Bulk capture session is already finished" }
        check(owner.accepting()) { "Bulk audit dispatcher is unavailable" }
        check(totalEntries < maxEntries) {
            "Bulk capture session '$source' exceeded its configured maximum of $maxEntries entries"
        }
        pending += entry
        totalEntries++
        if (pending.size >= chunkSize) {
            flushPendingLocked()
        }
    }

    /**
     * Finish the operation and release its reservation only after every queued chunk has drained into the normal
     * writer pipeline. Idempotent so both an abort path and WorldEdit's later extent commit may call it safely.
     */
    @Synchronized
    fun commit() {
        if (finished) return
        finished = true
        flushPendingLocked()
        owner.finishOperation(source)
    }

    /** Release an unused reservation. Only safe when no world mutation was captured. */
    @Synchronized
    fun releaseUnused() {
        if (finished) return
        check(totalEntries == 0 && pending.isEmpty()) { "Cannot release a bulk capture containing world changes" }
        finished = true
        owner.releaseReservation()
    }

    private fun flushPendingLocked() {
        if (pending.isEmpty()) return
        owner.enqueueChunk(source, pending.toList())
        pending.clear()
    }
}
