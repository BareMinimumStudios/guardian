package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockEntity.class)
public abstract class BlockEntityRemovalMixin {
    @WrapMethod(method = "setRemoved")
    private void guardian$invalidateRemoval(Operation<Void> original) {
        MinecraftInventoryCoordination.beforeBlockEntityRemoval((BlockEntity)(Object)this);
        original.call();
    }
}
