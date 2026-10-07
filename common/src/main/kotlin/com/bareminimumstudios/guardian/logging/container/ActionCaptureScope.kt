package com.bareminimumstudios.guardian.logging.container

import java.util.UUID

/** Nested operations on one player's action are covered by the outer snapshot. */
class ActionCaptureScope {
    private val active = ThreadLocal.withInitial { mutableSetOf<UUID>() }
    fun enter(playerId: UUID): Lease? {
        val owners = active.get()
        if (!owners.add(playerId)) return null
        return Lease(playerId, owners, Thread.currentThread())
    }
    inner class Lease internal constructor(private val playerId: UUID, private val owners: MutableSet<UUID>, private val thread: Thread) : AutoCloseable {
        private var closed = false
        override fun close() {
            check(Thread.currentThread() === thread) { "Capture scope must close on its owning thread" }
            if (closed) return
            closed = true
            check(owners.remove(playerId))
            if (owners.isEmpty()) active.remove()
        }
    }
}
