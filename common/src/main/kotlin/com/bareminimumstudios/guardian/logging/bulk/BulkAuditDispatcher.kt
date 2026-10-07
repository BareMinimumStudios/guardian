package com.bareminimumstudios.guardian.logging.bulk

import com.bareminimumstudios.guardian.domain.LogEntry
import com.bareminimumstudios.guardian.logging.BufferedLogPipeline
import com.bareminimumstudios.guardian.logging.PipelineState
import com.bareminimumstudios.guardian.logging.SubmissionResult
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Streaming bulk lane for trusted integrations such as WorldEdit.
 *
 * A reservation is acquired before the external operation mutates the world. Successful changes are streamed in
 * bounded chunks to this background dispatcher, which drains them into the normal single-writer pipeline without
 * silently dropping entries when that short-burst queue is full.
 *
 * This is process-crash safe only after entries reach the persistent writer; a future disk-backed ingress journal can
 * strengthen that guarantee. The current design intentionally fails closed on reservation/operation-limit exhaustion.
 */
class BulkAuditDispatcher(
    private val pipeline: BufferedLogPipeline,
    maxPendingOperations: Int,
    private val maxEntriesPerOperation: Int,
    private val chunkSize: Int = minOf(1_024, maxEntriesPerOperation),
    private val retryDelayMillis: Long = 2L
) {
    init {
        require(maxPendingOperations > 0)
        require(maxEntriesPerOperation > 0)
        require(chunkSize in 1..maxEntriesPerOperation)
        require(retryDelayMillis > 0)
    }

    private val logger = LoggerFactory.getLogger("Guardian/BulkAudit")
    private val reservations = Semaphore(maxPendingOperations, true)
    private val reservationCount = AtomicInteger()
    private val queuedOperationCount = AtomicInteger()
    private val queue = LinkedBlockingQueue<Task>()
    private val running = AtomicBoolean(false)
    private val worker = AtomicReference<Thread?>()
    private val queuedEntries = AtomicLong()
    private val submittedEntries = AtomicLong()
    private val streamedChunks = AtomicLong()
    private val backpressureRetries = AtomicLong()
    private val rejectedReservations = AtomicLong()
    private val failure = AtomicReference<Throwable?>()

    fun start() {
        check(running.compareAndSet(false, true)) { "Bulk audit dispatcher already started" }
        Thread(::runLoop, "Guardian-BulkAudit").also {
            it.isDaemon = true
            worker.set(it)
            it.start()
        }
    }

    internal fun accepting(): Boolean =
        running.get() && failure.get() == null && pipeline.currentState() == PipelineState.RUNNING

    fun tryBegin(source: String): BulkCaptureSession? {
        if (!accepting()) return null
        if (!reservations.tryAcquire()) {
            rejectedReservations.incrementAndGet()
            return null
        }
        reservationCount.incrementAndGet()
        return BulkCaptureSession(this, source, maxEntriesPerOperation, chunkSize)
    }

    internal fun enqueueChunk(source: String, entries: List<LogEntry>) {
        check(entries.isNotEmpty())
        check(accepting()) { "Bulk audit dispatcher is unavailable" }
        queuedEntries.addAndGet(entries.size.toLong())
        streamedChunks.incrementAndGet()
        queue.add(Task.Chunk(source, entries))
    }

    /**
     * Queue an operation boundary after all of its chunks. The single FIFO worker releases the reservation only when
     * that boundary is reached, proving that every earlier chunk from this operation was submitted to the writer.
     */
    internal fun finishOperation(source: String) {
        queuedOperationCount.incrementAndGet()
        queue.add(Task.Finish(source))
    }

    internal fun releaseReservation() {
        val remaining = reservationCount.decrementAndGet()
        check(remaining >= 0) { "Bulk audit reservation count became negative" }
        reservations.release()
    }

    fun metrics(): BulkAuditMetrics = BulkAuditMetrics(
        reservedOperations = reservationCount.get(),
        queuedOperations = queuedOperationCount.get(),
        queuedEntries = queuedEntries.get(),
        streamedChunks = streamedChunks.get(),
        submittedEntries = submittedEntries.get(),
        backpressureRetries = backpressureRetries.get(),
        rejectedReservations = rejectedReservations.get(),
        failed = failure.get() != null
    )

    fun lastFailure(): Throwable? = failure.get()

    fun stopGracefully(timeout: Duration): Boolean {
        require(!timeout.isNegative && !timeout.isZero)
        running.set(false)
        worker.get()?.interrupt()
        worker.get()?.join(timeout.toMillis())
        return worker.get()?.isAlive != true && queue.isEmpty() && reservationCount.get() == 0
    }

    private fun runLoop() {
        try {
            while (running.get() || queue.isNotEmpty()) {
                val task = try {
                    queue.poll(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                } ?: continue

                when (task) {
                    is Task.Chunk -> {
                        try {
                            drain(task)
                        } finally {
                            queuedEntries.addAndGet(-task.entries.size.toLong())
                        }
                    }
                    is Task.Finish -> {
                        queuedOperationCount.decrementAndGet()
                        releaseReservation()
                    }
                }
            }
        } catch (t: Throwable) {
            failure.set(t)
            running.set(false)
            logger.error("Guardian bulk audit dispatcher failed", t)
        }
    }

    private fun drain(batch: Task.Chunk) {
        for (entry in batch.entries) {
            while (true) {
                when (pipeline.submit(entry)) {
                    SubmissionResult.ACCEPTED -> {
                        submittedEntries.incrementAndGet()
                        break
                    }
                    SubmissionResult.BACKPRESSURE -> {
                        backpressureRetries.incrementAndGet()
                        pauseBeforeRetry()
                    }
                    SubmissionResult.NOT_RUNNING -> {
                        val state = pipeline.currentState()
                        if (!running.get() || state == PipelineState.FAILED || state == PipelineState.STOPPED) {
                            throw IllegalStateException(
                                "Audit pipeline became unavailable while draining bulk source '${batch.source}' (state=$state)"
                            )
                        }
                        pauseBeforeRetry()
                    }
                }
            }
        }
    }

    private fun pauseBeforeRetry() {
        try {
            Thread.sleep(retryDelayMillis)
        } catch (_: InterruptedException) {
            // Re-check lifecycle/pipeline state without discarding the current entry.
        }
    }

    private sealed interface Task {
        data class Chunk(val source: String, val entries: List<LogEntry>) : Task
        data class Finish(val source: String) : Task
    }
}
