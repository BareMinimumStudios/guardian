package com.bareminimumstudios.guardian.rollback

import java.util.concurrent.CompletableFuture

/** Trusted host acknowledgment: drain alone cannot resolve a stopped or late-committing operation. */
class ItemJournalRetention internal constructor(private val drained: CompletableFuture<Void>) {
    private val thread = Thread.currentThread()
    internal val barrier = CompletableFuture<Void>()
    val isDrained: Boolean get() {
        checkThread()
        return drained.isDone && !drained.isCompletedExceptionally && !drained.isCancelled
    }
    /** Call only after establishing the persistent outcome. This token provides no such proof itself. */
    fun releaseAfterReconciliation() {
        checkThread()
        check(isDrained) { "Journal work must actually drain before reconciliation releases owners" }
        barrier.complete(null)
    }
    private fun checkThread() = check(Thread.currentThread() === thread)
}
