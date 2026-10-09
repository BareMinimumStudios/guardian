package com.bareminimumstudios.guardian.rollback

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

enum class ItemOperationScopeState { OPEN, DRAINING, CLOSING_BACKEND, CLOSED, FAILED }

/**
 * Internal, bounded owner of one journal backend and up to 32 operation hosts.
 * A closing callback may own the backend only when external journal workers/writers are stopped.
 * Shared runtimes use a no-op callback and stopAndDrain(), then independently close the backend
 * after every producer drains. This scope never unregisters platform protection.
 * Close revokes admission first, stops hosts, then polls actual journal drain. Backend closure
 * runs off the owning thread exactly once. It never acknowledges an unresolved retention barrier.
 * When platform disk drain is registered, backend closure also waits for that actual drain.
 * A failed disk drain retains the backend and barriers for investigation. No platform lifecycle
 * is registered here; the caller must stop disk producers and supply their actual drain signal.
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
    private var stoppedJournalDrain: CompletionStage<Void>? = null
    private var diskDrain: CompletableFuture<Void>? = null
    var state = ItemOperationScopeState.OPEN; private set
    var failure: Throwable? = null; private set
    /** Observer cancellation cannot cancel backend closure or make advance report success. */
    val shutdown: CompletionStage<Void> get() { checkThread(); return backendClosed.minimalCompletionStage() }

    fun start(record: ItemRollbackRecord, coordination: ItemOwnerCoordination,
              bind: (ItemOwnerCoordination.Lease) -> ItemOperationPort,
              clock: () -> Long = System::nanoTime, requireDurableImages: Boolean = false): ItemOperationHost? {
        checkThread()
        check(state == ItemOperationScopeState.OPEN) { "Item operation admission is closed" }
        check(!advancing && !binding) { "Operation admission is not reentrant" }
        hosts.removeAll { it.state == ItemOperationState.COMPLETED }
        check(hosts.size < 32) { "Item operation scope is full" }
        // Binding invokes platform callbacks; close from a callback must not admit a late host.
        binding = true
        try {
            val host = ItemOperationHost.start(record, coordination, journal, bind, clock, requireDurableImages) ?: return null
            hosts.add(host)
            if (state != ItemOperationScopeState.OPEN) host.close()
            return host
        } finally { binding = false }
    }

    /** Production admission uses this path; complete persistent images are mandatory. */
    fun startProtected(record: ItemRollbackRecord, coordination: ItemOwnerCoordination,
                       bind: (ItemOwnerCoordination.Lease) -> ItemOperationPort,
                       clock: () -> Long = System::nanoTime): ItemOperationHost? =
        start(record, coordination, bind, clock, requireDurableImages = true)

    fun reconcile(host: ItemOperationHost, record: ItemRollbackRecord, port: ItemReconciliationPort,
                  actualDiskDrain: CompletionStage<Void>) {
        checkThread()
        check(state == ItemOperationScopeState.OPEN && !advancing && !binding)
        check(host in hosts) { "Operation does not belong to this scope" }
        binding = true
        try {
            host.reconcile(record, journal, port, actualDiskDrain)
            if (state != ItemOperationScopeState.OPEN) host.close()
        } finally { binding = false }
    }

    /** Attach the actual platform I/O drain before shutdown; observer futures are insufficient. */
    fun awaitDiskDrain(actualDrain: CompletionStage<Void>) {
        checkThread()
        check(state == ItemOperationScopeState.OPEN && !advancing && !binding)
        check(diskDrain == null) { "Platform disk drain is already registered" }
        // Keep an independent result: cancelling a caller's observer cannot signal our drain.
        val result = CompletableFuture<Void>()
        diskDrain = result
        actualDrain.whenComplete { _: Void?, error: Throwable? ->
            if (error == null) result.complete(null) else result.completeExceptionally(error)
        }
    }

    /** Stop only a host admitted by this scope. Its held owners remain until reconciliation. */
    fun cancel(host: ItemOperationHost) {
        checkThread()
        check(!advancing && !binding) { "Operation cancellation is not reentrant" }
        check(host in hosts) { "Operation does not belong to this scope" }
        host.close()
    }

    override fun close() {
        checkThread()
        if (state != ItemOperationScopeState.OPEN) return
        state = ItemOperationScopeState.DRAINING
        // State changes before platform callbacks, so they cannot admit another operation.
        hosts.toList().forEach { it.close() }
    }

    /**
     * Close all journal producers and return their physical drain, independently of tick polling.
     * This does not close the backend or release retained owners. A shared runtime must also
     * stop and drain its audit, history and disk producers before closing that backend.
     */
    fun stopAndDrain(): CompletionStage<Void> {
        checkThread()
        check(!advancing && !binding) { "Shutdown drain cannot be captured from a callback" }
        stoppedJournalDrain?.let { return it }
        close()
        return CompletableFuture.allOf(*hosts.map { it.journalDrain.toCompletableFuture() }.toTypedArray())
            .minimalCompletionStage().also { stoppedJournalDrain = it }
    }

    /** Nonblocking: the caller must continue polling on the owning thread after close. */
    fun advance(): ItemOperationScopeState {
        checkThread()
        check(!advancing && !binding) { "Item operation scope polling is not reentrant" }
        advancing = true
        try {
            when (state) {
                ItemOperationScopeState.OPEN -> {
                    // Callbacks may close the scope. A stable copy prevents late admission,
                    // while the state check leaves remaining hosts to shutdown polling.
                    for (host in hosts.toList()) {
                        if (state != ItemOperationScopeState.OPEN) break
                        host.advance()
                    }
                    hosts.removeAll { it.state == ItemOperationState.COMPLETED }
                }
                ItemOperationScopeState.DRAINING -> {
                    hosts.forEach { it.advance() }
                    if (hosts.all { it.isJournalDrained } && diskDrain?.isDone != false) {
                        try { diskDrain?.join() }
                        catch (error: RuntimeException) {
                            failure = error.cause ?: error
                            state = ItemOperationScopeState.FAILED
                            backendClosed.completeExceptionally(failure!!)
                            return state
                        }
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
