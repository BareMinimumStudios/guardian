package com.bareminimumstudios.guardian.logging.container

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Single-use action bracket. Snapshot capture itself remains on the server thread. */
class ContainerTransactionCorrelation(
    private val actor: ActorIdentity,
    private val menuId: Int,
    private val action: ContainerAction,
    private val before: InventorySnapshot,
    private val timestampEpochMillis: Long = System.currentTimeMillis(),
    private val transactionId: UUID = UUID.randomUUID(),
    private val contexts: List<ItemSlotOwner.BlockContainer> = emptyList()
) {
    private val finished = AtomicBoolean(false)
    init { require(menuId >= 0); require(timestampEpochMillis >= 0) }

    fun finish(after: InventorySnapshot, accepted: Boolean, crafted: Boolean = false): ContainerTransactionSnapshot? {
        check(finished.compareAndSet(false, true)) { "Container action was already completed" }
        if (!accepted) return null
        require(before.slots.keys == after.slots.keys) { "Container topology changed during the action" }
        val changes = before.slots.mapNotNull { (address, item) ->
            val next = after.slots.getValue(address)
            if (item == next) null else ItemSlotChange(address, item, next)
        }
        return if (changes.isEmpty()) null else ContainerTransactionSnapshot(
            transactionId, timestampEpochMillis, actor, menuId, if (crafted) ContainerAction.CRAFT else action, changes,
            (contexts + before.slots.keys.mapNotNull { it.owner as? ItemSlotOwner.BlockContainer }).distinct()
        )
    }
}
