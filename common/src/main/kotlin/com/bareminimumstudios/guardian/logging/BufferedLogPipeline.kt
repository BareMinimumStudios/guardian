package com.bareminimumstudios.guardian.logging

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.StorageBackend
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded single-writer audit pipeline.
 *
 * Important invariants:
 * - submit never blocks the server thread;
 * - queue saturation is explicit via BACKPRESSURE, never a silent drop;
 * - a batch removed from the queue is retried until it is committed or shutdown times out;
 * - only immutable LogEntry snapshots are accepted.
 */
class BufferedLogPipeline(
    private val storage: StorageBackend,
    queueCapacity: Int,
    private val batchSize: Int,
    private val flushIntervalMillis: Long,
    private val retryDelayMillis: Long = 250L
) : AutoCloseable {

    init {
        require(queueCapacity > 0) { "queueCapacity must be > 0" }
        require(batchSize > 0) { "batchSize must be > 0" }
        require(batchSize <= queueCapacity) { "batchSize cannot exceed queueCapacity" }
        require(flushIntervalMillis > 0) { "flushIntervalMillis must be > 0" }
        require(retryDelayMillis > 0) { "retryDelayMillis must be > 0" }
    }

    // This gate never covers storage I/O or callback completion. It orders accepted prefixes.
    private val submissionGate = ReentrantLock()
    private val barriers = linkedMapOf<CompletableFuture<AuditWriteFence>, Long>()
    private val maxBarriers = 32
    private class OwnerWatch(val owners: Set<ItemSlotOwner>) { val changed=mutableSetOf<ItemSlotOwner>() }
    private val ownerWatches=mutableSetOf<OwnerWatch>()

    /** Bounded watch, ordered with submit attempts; it retains no item snapshots or audit records. */
    fun observeOwners(owners: Collection<ItemSlotOwner>): AuditOwnerObservation = submissionGate.withLock {
        val copied=owners.toSet()
        require(copied.size in 1..32 && copied.all { it is ItemSlotOwner.BlockContainer || it is ItemSlotOwner.PlayerInventory })
        check(state.get()==PipelineState.RUNNING) { "Audit writer is unavailable" }
        check(ownerWatches.size<32) { "Too many owner observations" }
        val watch=OwnerWatch(copied);ownerWatches.add(watch)
        AuditOwnerObservation({ submissionGate.withLock {
            if(state.get()!=PipelineState.RUNNING || watch !in ownerWatches) watch.owners.toSet() else watch.changed.toSet()
        } }, { submissionGate.withLock { ownerWatches.remove(watch) } })
    }

    private fun invalidateOwners(entry: LogEntry) {
        if(ownerWatches.isEmpty()) return
        fun touch(owner: ItemSlotOwner) {
            val logical=when(owner) {
                is ItemSlotOwner.Cursor -> ItemSlotOwner.PlayerInventory(owner.playerId)
                is ItemSlotOwner.CraftingGrid -> ItemSlotOwner.PlayerInventory(owner.playerId)
                else -> owner
            }
            ownerWatches.forEach { if(logical in it.owners) it.changed.add(logical) }
        }
        when(entry) {
            is ContainerAuditEntry -> entry.transaction.changes.forEach { touch(it.address.owner) }
            is BlockChangeSnapshot -> touch(ItemSlotOwner.BlockContainer(entry.dimension,entry.position))
        }
    }
    private val queue = ArrayBlockingQueue<LogEntry>(queueCapacity)
    private val state = AtomicReference(PipelineState.CREATED)
    private val worker = AtomicReference<Thread?>()
    private val lastFailure = AtomicReference<Throwable?>(null)

    private val accepted = AtomicLong()
    private val persisted = AtomicLong()
    private val backpressure = AtomicLong()
    private val writeFailures = AtomicLong()

    fun start() {
        check(state.compareAndSet(PipelineState.CREATED, PipelineState.RUNNING)) {
            "Pipeline can only be started once; current state=${state.get()}"
        }

        try {
            storage.open()
            val thread = Thread(::writerLoop, "Guardian-LogWriter").apply {
                isDaemon = true
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, throwable ->
                    lastFailure.set(throwable)
                    this@BufferedLogPipeline.state.set(PipelineState.FAILED)
                    failBarriers(throwable)
                }
            }
            worker.set(thread)
            thread.start()
        } catch (throwable: Throwable) {
            lastFailure.set(throwable)
            state.set(PipelineState.FAILED)
            failBarriers(throwable)
            runCatching { storage.close() }
            throw throwable
        }
    }

    fun submit(entry: LogEntry): SubmissionResult = submissionGate.withLock {
        invalidateOwners(entry)
        if (state.get() != PipelineState.RUNNING) return@withLock SubmissionResult.NOT_RUNNING
        if (queue.offer(entry)) {
            accepted.incrementAndGet()
            SubmissionResult.ACCEPTED
        } else {
            backpressure.incrementAndGet()
            SubmissionResult.BACKPRESSURE
        }
    }

    /** Nonblocking accepted-prefix fence; capped separately from the audit queue. */
    fun writeBarrier(): AuditWriteBarrier {
        val completion=CompletableFuture<AuditWriteFence>()
        var failure: Throwable?=null
        val target=submissionGate.withLock {
            val value=accepted.get()
            if(state.get()!=PipelineState.RUNNING) failure=IllegalStateException("Guardian audit writer is not running")
            else if(barriers.size>=maxBarriers) failure=IllegalStateException("Too many pending audit barriers")
            else barriers[completion]=value
            value
        }
        failure?.let { completion.completeExceptionally(it) }
        return AuditWriteBarrier(target,completion) {
            val removed=submissionGate.withLock { barriers.remove(completion)!=null }
            if(removed) completion.completeExceptionally(CancellationException("Audit barrier cancelled"))
        }
    }

    /** Allocation-free lifecycle probe for hot-path integrations such as WorldEdit. */
    fun currentState(): PipelineState = state.get()

    fun metrics(): PipelineMetrics = PipelineMetrics(
        accepted = accepted.get(),
        persisted = persisted.get(),
        backpressure = backpressure.get(),
        writeFailures = writeFailures.get(),
        queued = queue.size,
        state = state.get()
    )

    fun lastFailure(): Throwable? = lastFailure.get()

    fun stopGracefully(timeout: Duration): Boolean {
        require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive" }

        submissionGate.withLock {
            when (checkNotNull(state.get())) {
                PipelineState.CREATED -> {
                    state.set(PipelineState.STOPPED)
                    return true
                }
                PipelineState.STOPPED -> return true
                PipelineState.FAILED -> return false
                PipelineState.RUNNING -> state.compareAndSet(PipelineState.RUNNING, PipelineState.STOPPING)
                PipelineState.STOPPING -> Unit
            }
        }
        failBarriers(IllegalStateException("Guardian audit writer is stopping"))

        val thread = worker.get() ?: return state.get() == PipelineState.STOPPED
        thread.interrupt()
        thread.join(timeout.toMillis())
        return !thread.isAlive && state.get() == PipelineState.STOPPED
    }

    override fun close() {
        stopGracefully(Duration.ofSeconds(10))
    }

    private fun writerLoop() {
        val pendingBatch = ArrayList<LogEntry>(batchSize)
        try {
            while (state.get() == PipelineState.RUNNING || queue.isNotEmpty() || pendingBatch.isNotEmpty()) {
                satisfyBarriers()
                if (pendingBatch.isEmpty()) {
                    val first = try {
                        queue.poll(flushIntervalMillis.coerceAtMost(100L), TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        null
                    }

                    if (first != null) {
                        pendingBatch += first
                        queue.drainTo(pendingBatch, batchSize - 1)
                    } else {
                        continue
                    }
                }

                try {
                    storage.append(pendingBatch)
                    persisted.addAndGet(pendingBatch.size.toLong())
                    pendingBatch.clear()
                    lastFailure.set(null)
                } catch (throwable: Throwable) {
                    writeFailures.incrementAndGet()
                    lastFailure.set(throwable)
                    // Keep the exact pending batch and retry it. Do not accept this as persisted.
                    try {
                        Thread.sleep(retryDelayMillis)
                    } catch (_: InterruptedException) {
                        // Re-check lifecycle immediately without discarding the pending batch.
                    }
                }
            }

            storage.flush()
            state.set(PipelineState.STOPPED)
        } catch (throwable: Throwable) {
            lastFailure.set(throwable)
            state.set(PipelineState.FAILED)
            failBarriers(throwable)
        } finally {
            runCatching { storage.close() }
                .onFailure { lastFailure.compareAndSet(null, it) }
        }
    }
    private fun satisfyBarriers() {
        val through=persisted.get()
        val ready=submissionGate.withLock { barriers.filterValues { it<=through }.keys.toList() }
        if(ready.isEmpty()) return
        try {
            storage.flush()
        } catch(error: Throwable) {
            writeFailures.incrementAndGet()
            lastFailure.set(error)
            failBarriers(error)
            return
        }
        val completed=submissionGate.withLock { if(state.get()==PipelineState.RUNNING) ready.mapNotNull { future -> barriers.remove(future)?.let { future to it } } else emptyList() }
        // Completing outside the gate also allows callbacks to submit or cancel another fence.
        completed.forEach { (future,target) -> future.complete(AuditWriteFence(target,through)) }
    }

    private fun failBarriers(error: Throwable) {
        val pending=submissionGate.withLock { ownerWatches.clear();barriers.keys.toList().also { barriers.clear() } }
        pending.forEach { it.completeExceptionally(error) }
    }

}
