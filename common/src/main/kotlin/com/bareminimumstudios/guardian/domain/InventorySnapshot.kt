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
    /** Stable entity identity with its historical location. This is not a rollback-capable owner. */
    data class EntityContainer(val dimension:ResourceId,val entityUuid:UUID,val entityType:ResourceId,val position:BlockPosition):ItemSlotOwner
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


/** Spatial query context only; an entity's location never becomes a block storage address. */
fun ItemSlotOwner.auditLocation():ItemSlotOwner.BlockContainer? = when(this) {
    is ItemSlotOwner.BlockContainer->this
    is ItemSlotOwner.EntityContainer->ItemSlotOwner.BlockContainer(dimension,position)
    else->null
}
