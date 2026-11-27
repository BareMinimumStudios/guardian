package com.bareminimumstudios.guardian.platform.minecraft;
import java.util.List;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Inventory;
/** Snapshot-only fixture; no gameplay callbacks may dereference the absent owner. */
public final class CraftingTestInventory {
    @SuppressWarnings("unchecked")
    public static void seed(CraftingContainer grid, int index, ItemStack item) {
        try {
            var field = grid.getClass().getDeclaredField("items"); field.setAccessible(true);
            ((List<ItemStack>)field.get(grid)).set(index,item);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    public static Inventory create() { return new Inventory(null); }
    public static InventoryMenu menu(Inventory inventory) { return new InventoryMenu(inventory,false,null); }
    public static InventoryMenu extended(Inventory inventory) { return new InventoryMenu(inventory,false,null) {}; }
}
