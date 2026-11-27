package com.bareminimumstudios.guardian.platform.minecraft;

import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;

/** Snapshot tests never call player behavior or recipe callbacks. */
public final class InventoryMenuFixtures {
    private InventoryMenuFixtures() {}
    public static Inventory inventory() { return new Inventory(null, new EntityEquipment()); }
    public static InventoryMenu create(Inventory inventory) {
        return new InventoryMenu(inventory, false, null);
    }
    /** Populate a snapshot-only fixture without invoking gameplay hooks on its deliberately absent owner. */
    public static void seed(Inventory inventory, int slot, ItemStack stack) {
        if(slot < 36) { inventory.getNonEquipmentItems().set(slot,stack);return; }
        try {
            var field=Inventory.class.getDeclaredField("equipment");field.setAccessible(true);
            ((EntityEquipment)field.get(inventory)).set(Inventory.EQUIPMENT_SLOT_MAPPING.get(slot),stack);
        } catch(ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
