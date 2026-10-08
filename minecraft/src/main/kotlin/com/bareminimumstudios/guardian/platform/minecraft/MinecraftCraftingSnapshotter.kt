package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.mixin.CraftingMenuAccessor
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.*

/** Exact vanilla layouts only. Result previews are intentionally excluded from stored items. */
object MinecraftCraftingSnapshotter {
    fun grid(menu: AbstractContainerMenu, inventory: Inventory): CraftingContainer? {
        val size = when (menu.javaClass) {
            InventoryMenu::class.java -> 4
            CraftingMenu::class.java -> 9
            else -> return null
        }
        if (menu.slots.size != 46) return null
        val result = menu.slots[0]
        if (result.javaClass != ResultSlot::class.java || result.container !is ResultContainer || result.containerSlot != 0) return null
        val grid = menu.slots[1].container as? CraftingContainer ?: return null
        if (grid.containerSize != size || grid.width != (if (size == 4) 2 else 3) || grid.height != grid.width) return null
        if ((1..size).any { menu.slots[it].container !== grid || menu.slots[it].containerSlot != it - 1 }) return null
        if ((size + 1 until menu.slots.size).any { !MinecraftInventorySnapshotter.isPlayerSlot(menu.slots[it], inventory) }) return null
        return grid
    }

    fun acceptsClick(menu: AbstractContainerMenu, inventory: Inventory, index: Int, type: ClickType = ClickType.PICKUP): Boolean =
        grid(menu, inventory) != null && (index == -999 || index in menu.slots.indices) && !(index == 0 && type == ClickType.THROW)

    fun capture(menu: AbstractContainerMenu, player: ServerPlayer): InventorySnapshot? =
        capture(menu, player.inventory, player.uuid, player.registryAccess())

    fun capture(menu: AbstractContainerMenu, inventory: Inventory, playerId: java.util.UUID, registries: net.minecraft.core.HolderLookup.Provider): InventorySnapshot? {
        val grid = grid(menu, inventory) ?: return null
        val slots = LinkedHashMap(MinecraftInventorySnapshotter.capturePlayer(menu, inventory, playerId, registries).slots)
        val owner = ItemSlotOwner.CraftingGrid(playerId, menu.containerId)
        for (index in 0 until grid.containerSize) slots[ItemSlotAddress(owner, index)] = MinecraftItemSnapshotter.capture(grid.getItem(index), registries)
        return InventorySnapshot(slots)
    }

    fun context(menu: AbstractContainerMenu, player: ServerPlayer): List<ItemSlotOwner.BlockContainer> {
        if (menu.javaClass != CraftingMenu::class.java) return emptyList()
        val address = (menu as CraftingMenuAccessor).`guardian$access`().evaluate({ level, pos ->
            if (level !== player.serverLevel()) null else ItemSlotOwner.BlockContainer(ResourceId.parse(level.dimension().location().toString()), BlockPosition(pos.x, pos.y, pos.z))
        })
        return address.orElse(null)?.let { listOf(it) } ?: emptyList()
    }
}
