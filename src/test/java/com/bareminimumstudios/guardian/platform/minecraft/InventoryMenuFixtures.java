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
}
