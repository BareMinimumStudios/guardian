package com.bareminimumstudios.guardian.rollback

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

enum class ItemOperationScopeState { OPEN, DRAINING, CLOSING_BACKEND, CLOSED, FAILED }

/**
 * Internal, bounded owner of one journal backend and up to 32 operation hosts.
 * The caller must give this scope sole use of the backend: external journal workers/writers
 * must already be stopped or use a different backend. This is not server lifecycle registration.
 * Close revokes admission first, stops hosts, then polls actual journal drain. Backend closure
 * runs off the owning thread exactly once. It never acknowledges an unresolved retention barrier.
 * Disk saves may finish later, but stopped hosts cannot submit another journal request.
 */
class ItemOperationScope(
    private val journal: ItemRollbackJournal,
    private val closeBackend: () -> Unit
) : AutoCloseable {
    private val thread = Thread.currentThread()
    private val hosts = arrayListOf<ItemOperationHost>()
    private val backendClosed = CompletableFuture<Void>()
    private var advancing = false
    private var binding = false
    var state = ItemOperationScopeState.OPEN; private set
    var failure: Throwable? = null; private set
    /** Observer cancellation cannot cancel backend closure or make advance report success. */
    val shutdown: CompletionStage<Void> get() { checkThread(); return backendClosed.minimalCompletionStage() }

    fun start(record: ItemRollbackRecord, coordination: ItemOwnerCoordination,
              bind: (ItemOwnerCoordination.Lease) -> ItemOperationPort,
              clock: () -> Long = System::nanoTime): ItemOperationHost? {
        checkThread()
        check(state == ItemOperationScopeState.OPEN) { "Item operation admission is closed" }
        check(!advancing && !binding) { "Operation admission is not reentrant" }
        hosts.removeAll { it.state == ItemOperationState.COMPLETED }
        check(hosts.size < 32) { "Item operation scope is full" }
        // Binding invokes platform callbacks; close from a callback must not admit a late host.
        binding = true
        try {
            val host = ItemOperationHost.start(record, coordination, journal, bind, clock) ?: return null
            hosts.add(host)
            if (state != ItemOperationScopeState.OPEN) host.close()
            return host
        } finally { binding = false }
    }

    override fun close() {
        checkThread()
        if (state != ItemOperationScopeState.OPEN) return
        state = ItemOperationScopeState.DRAINING
        // State changes before platform callbacks, so they cannot admit another operation.
        hosts.toList().forEach { it.close() }
    }

    /** Nonblocking: the caller must continue polling on the owning thread after close. */
    fun advance(): ItemOperationScopeState {
        checkThread()
        check(!advancing && !binding) { "Item operation scope polling is not reentrant" }
        advancing = true
        try {
            when (state) {
                ItemOperationScopeState.DRAINING -> {
                    hosts.forEach { it.advance() }
                    if (hosts.all { it.isJournalDrained }) {
                        state = ItemOperationScopeState.CLOSING_BACKEND
                        Thread({
                            try { closeBackend(); backendClosed.complete(null) }
                            catch (error: Throwable) { backendClosed.completeExceptionally(error) }
                        }, "Guardian item journal shutdown").apply { isDaemon = true }.start()
                    }
                }
                ItemOperationScopeState.CLOSING_BACKEND -> if (backendClosed.isDone) {
                    try { backendClosed.join(); state = ItemOperationScopeState.CLOSED }
                    catch (error: java.util.concurrent.CompletionException) {
                        failure = error.cause ?: error
                        state = ItemOperationScopeState.FAILED
                    }
                }
                else -> Unit
            }
            return state
        } finally { advancing = false }
    }
    private fun checkThread() = check(Thread.currentThread() === thread) { "Item operation scope controls must use its owning thread" }
}
