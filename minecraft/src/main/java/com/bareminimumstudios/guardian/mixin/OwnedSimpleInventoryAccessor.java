package com.bareminimumstudios.guardian.mixin;

import net.minecraft.core.NonNullList;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SimpleContainer.class)
public interface OwnedSimpleInventoryAccessor {
    @Accessor("items") NonNullList<ItemStack> guardian$getItems();
    @Mutable @Accessor("items") void guardian$setItems(NonNullList<ItemStack> items);
}
