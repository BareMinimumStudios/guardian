package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.ItemSlotOwner

/** Explicit single-owner authorization. No ambient permission is inherited by callbacks. */
class ItemWriteScope(private val coordination: ItemOwnerCoordination) {
    private val thread = Thread.currentThread()
    private var current: Permit? = null

    fun <T> write(lease: ItemOwnerCoordination.Lease, owner: ItemSlotOwner, action: (Permit) -> T): T {
        checkThread()
        check(current == null) { "Nested item write scopes are not allowed" }
        check(coordination.allowsMutation(owner, lease) && lease.isCurrent(lease.owners)) {
            "A current reservation for the target owner is required"
        }
        val permit = Permit(lease, owner)
        current = permit
        try {
            val result = action(permit)
            permit.requireCurrent(owner)
            return result
        } catch (failure: Throwable) {
            // Never revoke a replacement operation after this lease has gone stale.
            if (coordination.allowsMutation(owner, lease) && lease.isCurrent(lease.owners)) {
                coordination.invalidate(owner)
            }
            throw failure
        } finally {
            permit.active = false
            current = null
        }
    }

    inner class Permit internal constructor(
        private val lease: ItemOwnerCoordination.Lease,
        val owner: ItemSlotOwner
    ) {
        internal var active = true

        fun requireCurrent(target: ItemSlotOwner) {
            checkThread()
            check(active && current === this && target == owner &&
                coordination.allowsMutation(target, lease) && lease.isCurrent(lease.owners)) {
                "The item write permit is not current for this owner"
            }
        }
    }

    private fun checkThread() = check(Thread.currentThread() === thread) {
        "Item write scopes must use their owning thread"
    }
}
