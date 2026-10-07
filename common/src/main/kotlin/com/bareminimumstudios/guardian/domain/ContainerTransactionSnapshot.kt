package com.bareminimumstudios.guardian.domain

import java.util.Collections
import java.util.UUID

enum class ContainerAction { PICKUP, QUICK_MOVE, SWAP, CLONE, THROW, QUICK_CRAFT, PICKUP_ALL, CLOSE }
data class ItemSlotChange(val address: ItemSlotAddress, val before: ItemStackSnapshot, val after: ItemStackSnapshot) {
    init { require(before != after) { "An item change must change a slot" } }
}

/** One accepted action and all of its changed logical slots. Persistence is added in Step 4B. */
class ContainerTransactionSnapshot(
    val transactionId: UUID,
    val timestampEpochMillis: Long,
    val actor: ActorIdentity.Player,
    val menuId: Int,
    val action: ContainerAction,
    changes: List<ItemSlotChange>,
    containers: List<ItemSlotOwner.BlockContainer> = changes.mapNotNull { it.address.owner as? ItemSlotOwner.BlockContainer }.distinct()
) {
    val changes: List<ItemSlotChange> = Collections.unmodifiableList(ArrayList(changes))
    val containers: List<ItemSlotOwner.BlockContainer> = Collections.unmodifiableList(ArrayList(containers.distinct()))
    init {
        require(timestampEpochMillis >= 0)
        require(menuId >= 0)
        require(this.changes.isNotEmpty()) { "No-op actions must not create transactions" }
        require(this.changes.map { it.address }.toSet().size == this.changes.size) { "Duplicate logical slot" }
    }
}
