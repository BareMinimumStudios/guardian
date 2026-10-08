package com.bareminimumstudios.guardian.domain

import java.util.Collections
import java.util.UUID

/** Logical storage addresses, independent of a menu's display-slot numbering. */
sealed interface ItemSlotOwner {
    data class PlayerInventory(val playerId: UUID) : ItemSlotOwner
    data class Cursor(val playerId: UUID) : ItemSlotOwner
    /** Temporary ingredients; never a persistent block inventory or a recipe preview. */
    data class CraftingGrid(val playerId: UUID, val menuId: Int) : ItemSlotOwner { init { require(menuId >= 0) } }
    data class BlockContainer(val dimension: ResourceId, val position: BlockPosition) : ItemSlotOwner
}
data class ItemSlotAddress(val owner: ItemSlotOwner, val index: Int) {
    init {
        require(index >= 0) { "Slot index must not be negative" }
        require(owner !is ItemSlotOwner.Cursor || index == 0) { "A cursor has only slot zero" }
    }
}
class InventorySnapshot(slots: Map<ItemSlotAddress, ItemStackSnapshot>) {
    val slots: Map<ItemSlotAddress, ItemStackSnapshot> = Collections.unmodifiableMap(LinkedHashMap(slots))
}
