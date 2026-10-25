package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.DisplayInventoryAccess;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import java.util.List;

@Pseudo
@Mixin(targets={"net.satisfy.foundation.storage.StorageBlockEntity","net.satisfy.farm_and_charm.core.block.entity.StorageBlockEntity","net.satisfy.bakery.core.block.entity.StorageBlockEntity"}, remap=false)
public abstract class LetsDoDisplayInventoryMixin implements DisplayInventoryAccess {
    @Shadow(remap=false) public abstract NonNullList<ItemStack> getInventory();
    @Override public List<ItemStack> guardian$displayItems() { return getInventory(); }
}
