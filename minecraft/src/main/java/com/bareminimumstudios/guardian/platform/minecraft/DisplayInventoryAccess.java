package com.bareminimumstudios.guardian.platform.minecraft;

import net.minecraft.world.item.ItemStack;
import java.util.List;

/** Read access only. Implementing this does not make a display eligible for rollback. */
public interface DisplayInventoryAccess {
    List<ItemStack> guardian$displayItems();
}
