package com.bareminimumstudios.guardian.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemFrame.class)
public interface ItemFrameDataAccessor {
    @Accessor("DATA_ITEM")
    static EntityDataAccessor<ItemStack> guardian$itemData() { throw new UnsupportedOperationException(); }
    @Accessor("DATA_ROTATION")
    static EntityDataAccessor<Integer> guardian$rotationData() { throw new UnsupportedOperationException(); }
}
