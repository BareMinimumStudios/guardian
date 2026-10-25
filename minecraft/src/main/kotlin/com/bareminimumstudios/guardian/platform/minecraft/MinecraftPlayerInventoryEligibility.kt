package com.bareminimumstudios.guardian.platform.minecraft

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.inventory.InventoryMenu

/** Preflight only. Empty temporary ownership is not proof of exclusive mutation access. */
internal object MinecraftPlayerInventoryEligibility {
    fun isIdle(player: ServerPlayer): Boolean = failureReason(player)==null

    fun failureReason(player: ServerPlayer): String? {
        if (!player.isAlive) return "player is not alive"
        if (player.hasDisconnected()) return "player connection is disconnected"
        if (player.isChangingDimension) return "player is changing dimension"
        val menu = player.inventoryMenu
        if (player.containerMenu !== menu) return "an open menu is still active ("+player.containerMenu.javaClass.name+")"
        if (menu.javaClass != InventoryMenu::class.java) return "unsupported personal inventory menu ("+menu.javaClass.name+")"
        val grid = MinecraftCraftingSnapshotter.grid(menu, player.inventory) ?: return "personal crafting grid could not be identified"
        if (!menu.carried.isEmpty) return "cursor is holding an item"
        if (!menu.slots[0].item.isEmpty) return "personal crafting output slot is occupied"
        for(index in 0 until grid.containerSize) if(!grid.getItem(index).isEmpty) return "personal crafting input slot "+(index+1)+" is occupied"
        return null
    }
}
