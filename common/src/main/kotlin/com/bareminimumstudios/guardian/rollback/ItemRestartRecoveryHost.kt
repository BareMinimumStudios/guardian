package com.bareminimumstudios.guardian.rollback

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

enum class ItemRestartRecoveryState { WAITING_DISK, RECONCILING, DRAINING, COMPLETED, RECOVERY_REQUIRED }

/** Actual prefix drain plus a trusted read-only platform port; neither grants a write permit. */
class ItemRestartRecoveryBinding(val port: ItemReconciliationPort, val actualDiskDrain: CompletionStage<Void>)

/**
 * Explicit fresh-process ownership of a persisted unresolved receipt. Never replays, saves or
 * writes items. The old process must be gone; the platform must exclude mutations and account
 * for all current-process prior I/O. Queries used for admission must already be actually drained.
 */
class ItemRestartRecoveryHost private constructor(
    val lease: ItemOwnerCoordination.Lease,
    private val journal: ItemRollbackJournal,
    private val plan: ItemRollbackRecord,
    private val retention: ItemJournalRetention,
    private var worker: ItemRollbackJournalWorker,
    private val clock: () -> Long
) : AutoCloseable {
    private val thread = Thread.currentThread()
    private var binding: ItemRestartRecoveryBinding? = null
    private var disk: CompletableFuture<Void>? = null
    private var driver: ItemReconciliationDriver? = null
    private var started = clock()
    private var advancing = false
    private var bindingCallback = false
    private var stopped = false
    private var attempts = 0
    private var record = plan
    var state = ItemRestartRecoveryState.WAITING_DISK; private set
    var reason: String? = null; private set
    var failure: Exception? = null; private set
    val isJournalDrained: Boolean get() { checkThread();return worker.drained.toCompletableFuture().isDone }
    /** Capture after close; observers cannot cancel actual work or backend shutdown. */
    val journalDrain: CompletionStage<Void> get() { checkThread();check(worker.isStopped);return worker.drained }

    fun advance(): ItemRestartRecoveryState {
        checkThread();check(!advancing && !bindingCallback)
        if(state in setOf(ItemRestartRecoveryState.COMPLETED,ItemRestartRecoveryState.RECOVERY_REQUIRED))return state
        advancing=true
        try {
            when(state) {
                ItemRestartRecoveryState.WAITING_DISK -> {
                    if(clock()-started > TimeUnit.SECONDS.toNanos(10))drain(false,"Prior physical disk work did not drain in time")
                    else if(disk?.isDone == true) {
                        disk!!.join()
                        check(lease.isRetained(lease.owners) && !lease.isCurrent(lease.owners))
                        check(checkNotNull(binding).port.isExclusiveAndQuiescent(lease.owners))
                        if(stopped || state!=ItemRestartRecoveryState.WAITING_DISK)return state
                        driver=ItemReconciliationDriver(record,worker,binding!!.port,clock)
                        state=ItemRestartRecoveryState.RECONCILING
                    }
                }
                ItemRestartRecoveryState.RECONCILING -> {
                    val result=checkNotNull(driver).advance()
                    if(state != ItemRestartRecoveryState.RECONCILING)return state
                    if(result in setOf(ItemReconciliationState.RESOLVED,ItemReconciliationState.ALREADY_RESOLVED,ItemReconciliationState.UNRESOLVED)) {
                        drain(result != ItemReconciliationState.UNRESOLVED,driver?.reason)
                    }
                }
                ItemRestartRecoveryState.DRAINING -> finishDrain()
                else -> Unit
            }
        } catch(error: Exception) {failure=error;drain(false,"Restart reconciliation failed; ownership remains protected")}
        finally {advancing=false}
        return state
    }

    private var resolved = false
    private fun drain(success: Boolean,message: String?) {
        resolved=success && !stopped
        if(message!=null)reason=message
        driver?.stop();worker.close();state=ItemRestartRecoveryState.DRAINING
    }

    private fun finishDrain() {
        if(!isJournalDrained || !retention.isDrained)return
        val success=resolved && !stopped && lease.isRetained(lease.owners)
        val old=binding;binding=null
        try {(old?.port as? AutoCloseable)?.close()}
        catch(_: Exception) {resolved=false;reason="Read-only resource closure failed"}
        // Close callbacks may stop this host or registry. No such callback can release ownership.
        if(success && resolved && !stopped && lease.isRetained(lease.owners)) {
            retention.releaseAfterReconciliation();state=ItemRestartRecoveryState.COMPLETED
        } else state=ItemRestartRecoveryState.RECOVERY_REQUIRED
    }

    override fun close() {
        checkThread()
        if(state==ItemRestartRecoveryState.COMPLETED)return
        stopped=true
        drain(false,"Restart recovery stopped; durable outcome requires reconciliation")
        val old=binding;binding=null
        runCatching {(old?.port as? AutoCloseable)?.close()}
    }

    /** Explicit retry retains the exact revoked lease; stale records cannot replace its plan. */
    fun retry(fresh: ItemRollbackRecord,bind: (ItemOwnerCoordination.Lease) -> ItemRestartRecoveryBinding) {
        checkThread();check(!advancing && !bindingCallback && !stopped)
        check(state==ItemRestartRecoveryState.RECOVERY_REQUIRED && isJournalDrained)
        require(fresh.phase in setOf(ItemRollbackPhase.COMPLETED,ItemRollbackPhase.RECOVERY_REQUIRED))
        require(sameSaveRecord(plan,fresh,fresh.phase))
        check(lease.isRetained(lease.owners) && attempts < 8)
        worker=ItemRollbackJournalWorker(journal);record=fresh
        bindAttempt(bind)
    }

    private fun bindAttempt(bind: (ItemOwnerCoordination.Lease) -> ItemRestartRecoveryBinding) {
        attempts++;started=clock();driver=null;resolved=false;reason=null;failure=null
        state=ItemRestartRecoveryState.WAITING_DISK;bindingCallback=true
        try {
            val value=bind(lease)
            if(stopped) {(value.port as? AutoCloseable)?.close();return}
            binding=value
            // The caller supplies an actual protected drain, not an arbitrary observer future.
            disk=value.actualDiskDrain.thenApply { it }.toCompletableFuture()
        } catch(error: Exception) {failure=error;drain(false,"Read-only recovery binding is unavailable; ownership remains protected")}
        finally {bindingCallback=false}
    }

    private fun checkThread()=check(Thread.currentThread()===thread)

    companion object {
        /** Receipt loading and old-process termination are admission prerequisites, not inferred here. */
        fun start(receipt: ItemRollbackImageRecord,coordination: ItemOwnerCoordination,journal: ItemRollbackJournal,
                  bind: (ItemOwnerCoordination.Lease) -> ItemRestartRecoveryBinding,
                  clock: () -> Long = System::nanoTime): ItemRestartRecoveryHost? {
            require(!receipt.acknowledged) { "A resolved receipt needs no new owner protection" }
            val record=receipt.images.record
            require(record.phase in setOf(ItemRollbackPhase.COMPLETED,ItemRollbackPhase.RECOVERY_REQUIRED))
            ItemRollbackImages(record,receipt.images.original)
            val owners=ItemRecoveryCheck(record).owners.toSet()
            val lease=coordination.acquire(record.operationId,owners) ?: return null
            val worker=try {ItemRollbackJournalWorker(journal)} catch(error: Exception) {lease.close();throw error}
            val retention=lease.retainUntilJournalReconciled(worker)
            lease.close()
            return ItemRestartRecoveryHost(lease,journal,record,retention,worker,clock).also {it.bindAttempt(bind)}
        }
    }
}
