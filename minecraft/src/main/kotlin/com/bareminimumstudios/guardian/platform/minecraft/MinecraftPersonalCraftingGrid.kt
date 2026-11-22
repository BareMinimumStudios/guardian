package com.bareminimumstudios.guardian.platform.minecraft

import net.minecraft.world.inventory.CraftingContainer
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.inventory.ResultContainer
import net.minecraft.world.inventory.ResultSlot

/** Checks temporary ownership without rejecting unrelated mod-added equipment slots. */
internal object MinecraftPersonalCraftingGrid {
    fun read(menu: InventoryMenu): CraftingContainer? {
        if (menu.slots.size < 5) return null
        val result = menu.slots[0]
        if (result !is ResultSlot || result.container !is ResultContainer || result.containerSlot != 0) return null
        val grid = menu.slots[1].container as? CraftingContainer ?: return null
        if (grid.containerSize != 4 || grid.width != 2 || grid.height != 2) return null
        for (index in 1..4) {
            val slot = menu.slots[index]
            if (slot.container !== grid || slot.containerSlot != index - 1) return null
        }
        // Equipment outside the image is allowed; ambiguous temporary aliases are not.
        if (menu.slots.drop(5).any { it.container === grid || it.container === result.container }) return null
        return grid
    }
}
