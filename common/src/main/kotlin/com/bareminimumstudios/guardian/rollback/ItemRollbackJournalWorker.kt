package com.bareminimumstudios.guardian.rollback

import java.util.UUID
import com.bareminimumstudios.guardian.domain.InventorySnapshot
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Single bounded journal worker shared by apply and save protocols. Owns no inventory or backend.
 * Requests/close belong to the creating thread. Close rejects queued work without interrupting
 * already-started journal operations; drained completes only after all their results settle.
 * The host must retain exclusion and keep the backend open until drain, then reconcile results.
 */
class ItemRollbackJournalWorker(private val journal: ItemRollbackJournal) : ItemApplyJournalPort, ItemSaveJournalPort, ItemReconciliationJournalPort, AutoCloseable {
    private val thread = Thread.currentThread()
    private val lock = Any()
    private val tasks = linkedSetOf<Request<*>>()
    private var stopped = false
    private val drain = CompletableFuture<Void>()
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(32), { task ->
        Thread(task, "Guardian item rollback journal").apply { isDaemon = true }
    }, ThreadPoolExecutor.AbortPolicy())

    /** Closing this worker does not close the caller-owned journal backend. */
    val drained: CompletionStage<Void> get() { checkThread(); return drain.minimalCompletionStage() }
    val isStopped: Boolean get() { checkThread(); return synchronized(lock) { stopped } }

    /** Caller must wait for confirmed protection before admitting any inventory writes. */
    fun protect(record: ItemRollbackRecord): CompletionStage<Boolean> {
        checkThread()
        require(record.phase == ItemRollbackPhase.PREPARED)
        ItemRecoveryCheck(record)
        return submit { (journal as? ItemRollbackProtectionJournal)?.protectItemRollback(record) ?: false }
    }

    fun protect(record: ItemRollbackRecord, original: InventorySnapshot): CompletionStage<Boolean> {
        checkThread();require(record.phase==ItemRollbackPhase.PREPARED)
        val immutable=ItemRollbackImages(record,original)
        return submit { (journal as? ItemRollbackProtectionJournal)?.protectItemRollback(record,immutable.original) ?: false }
    }
    override fun readImages(operationId: UUID): CompletionStage<ItemRollbackImageRecord?> = submit {
        (journal as? ItemRollbackProtectionJournal)?.itemRollbackImages(operationId)
    }
    override fun acknowledge(record: ItemRollbackRecord, images: ItemRollbackImages, saved: InventorySnapshot): CompletionStage<Boolean> {
        checkThread();require(record.phase==ItemRollbackPhase.COMPLETED || record.phase==ItemRollbackPhase.RECOVERY_REQUIRED)
        ItemRecoveryCheck(record)
        return submit { (journal as? ItemRollbackProtectionJournal)?.acknowledgeItemRollback(record,images,saved) ?: false }
    }

    override fun read(operationId: UUID): CompletionStage<ItemRollbackRecord?> = submit { journal.itemRollback(operationId) }
    override fun markApplying(operationId: UUID): CompletionStage<Boolean> = submit {
        journal.transitionItemRollback(operationId, ItemRollbackPhase.PREPARED, ItemRollbackPhase.APPLYING)
    }
    override fun complete(record: ItemRollbackRecord): CompletionStage<Boolean> {
        checkThread()
        require(record.phase == ItemRollbackPhase.APPLYING || record.phase == ItemRollbackPhase.RECOVERY_REQUIRED)
        ItemRecoveryCheck(record)
        return submit {
            sameSaveRecord(record, journal.itemRollback(record.operationId)) &&
                journal.transitionItemRollback(record.operationId, record.phase, ItemRollbackPhase.COMPLETED)
        }
    }

    override fun close() {
        checkThread()
        val rejected = synchronized(lock) {
            if (stopped) return
            stopped = true
            tasks.filter { !it.started }.also { queued ->
                queued.forEach { it.cancelled = true; executor.remove(it) }
            }
        }
        // State is closed before notifying callbacks, which may attempt more requests.
        rejected.forEach {
            it.reject(RejectedExecutionException("Item journal worker stopped before request started"))
            synchronized(lock) { tasks.remove(it) }
        }
        executor.shutdown()
        completeDrainIfStopped()
    }

    private fun <T> submit(action: () -> T): CompletionStage<T> {
        checkThread()
        val request = Request(action)
        val failure = synchronized(lock) {
            if (stopped) RejectedExecutionException("Item journal worker is stopped") else {
                tasks.add(request)
                try { executor.execute(request); null } catch (error: RejectedExecutionException) { tasks.remove(request); error }
            }
        }
        if (failure != null) request.reject(failure)
        // A caller's cancellation must not mask or cancel an actual journal operation.
        return request.result.minimalCompletionStage()
    }

    private inner class Request<T>(private val action: () -> T) : Runnable {
        var started = false // guarded by lock
        var cancelled = false // guarded by lock
        val result = CompletableFuture<T>()
        fun reject(failure: Exception) { result.completeExceptionally(failure) }
        override fun run() {
            synchronized(lock) {
                if (this !in tasks || cancelled) return
                check(!stopped) { "A stopped queued request cannot access the journal" }
                started = true
            }
            try {
                check(Thread.currentThread() !== thread)
                result.complete(action())
            } catch (failure: Throwable) { result.completeExceptionally(failure) }
            finally {
                synchronized(lock) { tasks.remove(this) }
                completeDrainIfStopped()
            }
        }
    }
    private fun completeDrainIfStopped() {
        if (synchronized(lock) { stopped && tasks.isEmpty() }) drain.complete(null)
    }
    private fun checkThread() = check(Thread.currentThread() === thread) { "Item journal requests and shutdown must use the owning driver thread" }
}
