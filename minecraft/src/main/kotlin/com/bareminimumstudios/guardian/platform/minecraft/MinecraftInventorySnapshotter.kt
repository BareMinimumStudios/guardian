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

/** Only player-owned slots are captured here; crafting needs its own complete correlation. */
object MinecraftInventorySnapshotter {
    fun isPlayerSlot(slot: Slot, inventory: Inventory): Boolean =
        slot.container === inventory && slot.containerSlot in 0 until inventory.containerSize

    fun supportsInventoryMenu(menu: AbstractContainerMenu, inventory: Inventory): Boolean {
        // Reject extensions with extra or replaced slots instead of recording an incomplete action.
        if (menu.javaClass != InventoryMenu::class.java || menu.slots.size != 46) return false
        if (menu.slots[0].container !is ResultContainer) return false
        val crafting = menu.slots[1].container
        if (crafting !is CraftingContainer || crafting.containerSize != 4) return false
        if ((1..4).any { menu.slots[it].container !== crafting }) return false
        if ((0..4).any { !menu.slots[it].item.isEmpty }) return false
        return (5..45).all { isPlayerSlot(menu.slots[it], inventory) }
    }

    fun acceptsInventoryClick(menu: AbstractContainerMenu, inventory: Inventory, index: Int): Boolean =
        supportsInventoryMenu(menu, inventory) &&
            (index == -999 || index in menu.slots.indices && isPlayerSlot(menu.slots[index], inventory))

    fun capturePlayer(menu: AbstractContainerMenu, inventory: Inventory, playerId: UUID, registries: HolderLookup.Provider): InventorySnapshot {
        val slots = LinkedHashMap<ItemSlotAddress, ItemStackSnapshot>()
        for (index in 0 until inventory.containerSize) {
            slots[ItemSlotAddress(ItemSlotOwner.PlayerInventory(playerId), index)] = MinecraftItemSnapshotter.capture(inventory.getItem(index), registries)
        }
        slots[ItemSlotAddress(ItemSlotOwner.Cursor(playerId), 0)] = MinecraftItemSnapshotter.capture(menu.carried, registries)
        return InventorySnapshot(slots)
    }
}
