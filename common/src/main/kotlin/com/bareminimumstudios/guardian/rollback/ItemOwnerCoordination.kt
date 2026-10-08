package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.ItemSlotOwner
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class ItemOwnerLeaseState { ACTIVE, RELEASED, INVALIDATED, EXPIRED, STOPPED }

/** Reservations only. Platform mutation hooks and identity checks must establish gameplay exclusion. */
class ItemOwnerCoordination(private val clock: () -> Long = System::nanoTime) {
    private val thread = Thread.currentThread()
    private val operations = linkedMapOf<UUID, Lease>()
    private val reserved = mutableMapOf<ItemSlotOwner, Lease>()
    private var stopped = false

    fun acquire(operationId: UUID, owners: Collection<ItemSlotOwner>): Lease? {
        checkThread()
        reapExpired()
        if (stopped || operations.size >= 32 || operationId in operations) return null
        if (owners.isEmpty() || owners.size > 32) return null
        val copy = owners.toSet()
        if (copy.size != owners.size || copy.any { !persistent(it) || it in reserved }) return null
        return Lease(operationId, Collections.unmodifiableSet(copy), clock()).also { lease ->
            operations[operationId] = lease
            copy.forEach { reserved[it] = lease }
        }
    }

    fun allowsMutation(owner: ItemSlotOwner, permit: Lease? = null): Boolean {
        checkThread()
        reapExpired()
        if (stopped) return false
        // A supplied stale/foreign permit must never become ordinary uncoordinated access.
        if (permit != null && (operations[permit.operationId] !== permit || owner !in permit.owners)) return false
        val holder = reserved[owner] ?: return permit == null
        return holder === permit
    }

    fun invalidate(owner: ItemSlotOwner) {
        checkThread()
        reapExpired()
        reserved[owner]?.let { finish(it, ItemOwnerLeaseState.INVALIDATED) }
    }

    fun stop() {
        checkThread()
        if (stopped) return
        stopped = true
        operations.values.toList().forEach { finish(it, ItemOwnerLeaseState.STOPPED) }
    }

    inner class Lease internal constructor(
        val operationId: UUID,
        val owners: Set<ItemSlotOwner>,
        internal val started: Long
    ) : AutoCloseable {
        internal var currentState = ItemOwnerLeaseState.ACTIVE
        val state: ItemOwnerLeaseState
            get() {
                checkThread()
                reapExpired()
                return currentState
            }

        fun isCurrent(expectedOwners: Set<ItemSlotOwner>): Boolean {
            checkThread()
            reapExpired()
            return !stopped && currentState == ItemOwnerLeaseState.ACTIVE &&
                operations[operationId] === this && owners == expectedOwners &&
                owners.all { reserved[it] === this }
        }

        override fun close() {
            checkThread()
            reapExpired()
            if (currentState == ItemOwnerLeaseState.ACTIVE) finish(this, ItemOwnerLeaseState.RELEASED)
        }
    }

    private fun reapExpired() {
        val now = clock()
        operations.values.filter { now - it.started >= TimeUnit.SECONDS.toNanos(10) }
            .forEach { finish(it, ItemOwnerLeaseState.EXPIRED) }
    }

    private fun finish(lease: Lease, state: ItemOwnerLeaseState) {
        if (operations[lease.operationId] !== lease) return
        operations.remove(lease.operationId)
        lease.owners.forEach { if (reserved[it] === lease) reserved.remove(it) }
        lease.currentState = state
    }

    private fun persistent(owner: ItemSlotOwner) =
        owner is ItemSlotOwner.BlockContainer || owner is ItemSlotOwner.PlayerInventory

    private fun checkThread() = check(Thread.currentThread() === thread) {
        "Inventory coordination must use its owning thread"
    }
}
