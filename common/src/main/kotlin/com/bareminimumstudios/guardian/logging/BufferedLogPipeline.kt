package com.bareminimumstudios.guardian.logging

import com.bareminimumstudios.guardian.domain.LogEntry
import com.bareminimumstudios.guardian.storage.StorageBackend
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
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
                }
            }
            worker.set(thread)
            thread.start()
        } catch (throwable: Throwable) {
            lastFailure.set(throwable)
            state.set(PipelineState.FAILED)
            runCatching { storage.close() }
            throw throwable
        }
    }

    fun submit(entry: LogEntry): SubmissionResult {
        if (state.get() != PipelineState.RUNNING) return SubmissionResult.NOT_RUNNING
        return if (queue.offer(entry)) {
            accepted.incrementAndGet()
            SubmissionResult.ACCEPTED
        } else {
            backpressure.incrementAndGet()
            SubmissionResult.BACKPRESSURE
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
                if (pendingBatch.isEmpty()) {
                    val first = try {
                        queue.poll(flushIntervalMillis, TimeUnit.MILLISECONDS)
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
        } finally {
            runCatching { storage.close() }
                .onFailure { lastFailure.compareAndSet(null, it) }
        }
    }
}
