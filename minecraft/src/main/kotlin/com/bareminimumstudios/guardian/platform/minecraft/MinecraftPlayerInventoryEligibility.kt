package com.bareminimumstudios.guardian.platform.minecraft

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.inventory.InventoryMenu

/** Preflight only. Empty temporary ownership is not proof of exclusive mutation access. */
internal object MinecraftPlayerInventoryEligibility {
    fun isIdle(player: ServerPlayer): Boolean {
        if (!player.isAlive || player.hasDisconnected() || player.isChangingDimension) return false
        val menu = player.inventoryMenu
        if (player.containerMenu !== menu || menu.javaClass != InventoryMenu::class.java) return false
        val grid = MinecraftCraftingSnapshotter.grid(menu, player.inventory) ?: return false
        return menu.carried.isEmpty && menu.slots[0].item.isEmpty &&
            (0 until grid.containerSize).all { grid.getItem(it).isEmpty }
    }
}
