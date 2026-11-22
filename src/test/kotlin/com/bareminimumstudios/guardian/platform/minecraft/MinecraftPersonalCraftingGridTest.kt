package com.bareminimumstudios.guardian.platform.minecraft

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.SimpleContainer
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.test.*

class MinecraftPersonalCraftingGridTest {
    companion object { init { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap() } }
    @Test fun addedEquipmentSlotsDoNotHideThePersonalCraftingGrid() {
        val inventory=InventoryMenuFixtures.inventory()
        val menu=InventoryMenuFixtures.create(inventory)
        val grid=assertNotNull(MinecraftPersonalCraftingGrid.read(menu))
        val equipment=SimpleContainer(3)
        equipment.setItem(0,ItemStack(Items.DIAMOND))
        repeat(3) { menu.slots.add(Slot(equipment,it,0,0)) }
        assertSame(grid,MinecraftPersonalCraftingGrid.read(menu))
        assertNull(MinecraftCraftingSnapshotter.grid(menu,inventory),"Transaction capture retains its stricter layout contract")
        assertEquals(1,equipment.getItem(0).count)
    }
    @Test fun replacedInputsAndAliasedTemporarySlotsAreRefused() {
        val inventory=InventoryMenuFixtures.inventory()
        val menu=InventoryMenuFixtures.create(inventory)
        val grid=assertNotNull(MinecraftPersonalCraftingGrid.read(menu))
        menu.slots.add(Slot(grid,0,0,0))
        assertNull(MinecraftPersonalCraftingGrid.read(menu))
        menu.slots.removeAt(menu.slots.lastIndex)
        menu.slots[2]=Slot(SimpleContainer(4),1,0,0)
        assertNull(MinecraftPersonalCraftingGrid.read(menu))
    }
}
