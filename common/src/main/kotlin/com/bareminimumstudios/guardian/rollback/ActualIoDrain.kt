package com.bareminimumstudios.guardian.rollback

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean

/** Physical work accounting. Close admission before interpreting drain as quiescence. */
class ActualIoDrain(private val capacity: Int = 128) : AutoCloseable {
    private val gate = Any()
    private var stopped = false
    private var active = 0
    private var sequence = 0L
    private val tickets = linkedSetOf<Long>()
    private val fences = linkedMapOf<CompletableFuture<Void>,Long>()
    private val completion = CompletableFuture<Void>()
    init { require(capacity > 0) }
    val drained: CompletionStage<Void> get() = completion.minimalCompletionStage()
    val isDrained: Boolean get() = synchronized(gate) { stopped && active == 0 }
    fun begin(): Ticket = synchronized(gate) {
        check(!stopped) { "Physical I/O admission is closed" }
        check(active < capacity) { "Too much physical I/O work" }
        check(sequence < Long.MAX_VALUE)
        active++
        val id = ++sequence
        tickets.add(id)
        Ticket(id)
    }
    /** Prior physical requests only. Caller must stop their producers before claiming quiescence. */
    fun fence(): CompletionStage<Void> = synchronized(gate) {
        check(fences.size < 32) { "Too many pending physical I/O fences" }
        val result = CompletableFuture<Void>()
        if(tickets.isEmpty())result.complete(null) else fences[result] = sequence
        result.minimalCompletionStage()
    }
    inner class Ticket internal constructor(private val id: Long) : AutoCloseable {
        private val finished = AtomicBoolean()
        override fun close() {
            if (!finished.compareAndSet(false, true)) return
            val (ready, completedFences) = synchronized(gate) {
                check(active > 0 && tickets.remove(id)); active--
                val finished = fences.filterValues { target -> tickets.none { it <= target } }.keys.toList()
                finished.forEach { fences.remove(it) }
                (stopped && active == 0) to finished
            }
            completedFences.forEach { it.complete(null) }
            if (ready) completion.complete(null)
        }
    }
    override fun close() {
        val ready = synchronized(gate) { stopped = true; active == 0 }
        if (ready) completion.complete(null)
    }
}
