package com.bareminimumstudios.guardian.platform.minecraft;
import net.minecraft.world.entity.player.Inventory;
/** Snapshot-only fixture; no gameplay callbacks may dereference the absent owner. */
public final class CraftingTestInventory {
    @SuppressWarnings("unchecked")
    public static void seed(net.minecraft.world.inventory.CraftingContainer grid, int index, net.minecraft.world.item.ItemStack item) {
        try {
            var field = grid.getClass().getDeclaredField("items"); field.setAccessible(true);
            ((java.util.List<net.minecraft.world.item.ItemStack>)field.get(grid)).set(index,item);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    public static Inventory create() { return new Inventory(null); }
    public static net.minecraft.world.inventory.InventoryMenu menu(Inventory inventory) { return new net.minecraft.world.inventory.InventoryMenu(inventory,false,null); }
    public static net.minecraft.world.inventory.InventoryMenu extended(Inventory inventory) { return new net.minecraft.world.inventory.InventoryMenu(inventory,false,null) {}; }
}
