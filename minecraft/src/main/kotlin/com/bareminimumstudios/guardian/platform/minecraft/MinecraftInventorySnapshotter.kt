package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import net.minecraft.core.HolderLookup
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.CraftingContainer
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.inventory.ResultContainer
import net.minecraft.world.inventory.Slot
import java.util.UUID

/** Complete player inventory and cursor snapshots; transient grids are captured separately. */
object MinecraftInventorySnapshotter {
    fun isPlayerSlot(slot: Slot, inventory: Inventory): Boolean =
        slot.container === inventory && slot.containerSlot in 0 until inventory.containerSize

    fun capturePlayer(menu: AbstractContainerMenu, inventory: Inventory, playerId: UUID, registries: HolderLookup.Provider): InventorySnapshot {
        val slots = LinkedHashMap<ItemSlotAddress, ItemStackSnapshot>()
        for (index in 0 until inventory.containerSize) {
            slots[ItemSlotAddress(ItemSlotOwner.PlayerInventory(playerId), index)] = MinecraftItemSnapshotter.capture(inventory.getItem(index), registries)
        }
        slots[ItemSlotAddress(ItemSlotOwner.Cursor(playerId), 0)] = MinecraftItemSnapshotter.capture(menu.carried, registries)
        return InventorySnapshot(slots)
    }
}
