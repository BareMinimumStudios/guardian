package com.bareminimumstudios.guardian.platform.minecraft;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;

/** Snapshot tests never call player behavior or recipe callbacks. */
public final class InventoryMenuFixtures {
    private InventoryMenuFixtures() {}
    public static Inventory inventory() { return new Inventory(null); }
    public static InventoryMenu create(Inventory inventory) {
        return new InventoryMenu(inventory, false, null);
    }
    public static void seed(Inventory inventory,int slot,net.minecraft.world.item.ItemStack stack) {
        if(slot<36)inventory.items.set(slot,stack);
        else if(slot<40)inventory.armor.set(slot-36,stack);
        else if(slot==40)inventory.offhand.set(0,stack);
        else throw new IllegalArgumentException("Unknown inventory slot");
    }
}
