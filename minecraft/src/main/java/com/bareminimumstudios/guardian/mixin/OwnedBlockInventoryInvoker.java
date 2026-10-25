package com.bareminimumstudios.guardian.mixin;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BaseContainerBlockEntity.class)
public interface OwnedBlockInventoryInvoker {
    @Invoker("getItems") NonNullList<ItemStack> guardian$getItems();
    @Invoker("setItems") void guardian$setItems(NonNullList<ItemStack> items);
}
