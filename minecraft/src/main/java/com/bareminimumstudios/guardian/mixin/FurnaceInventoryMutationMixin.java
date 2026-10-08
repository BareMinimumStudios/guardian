package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class FurnaceInventoryMutationMixin {
    @Inject(method = "setItem", at = @At("HEAD"))
    private void guardian$beforeWrite(CallbackInfo callback) {
        MinecraftInventoryCoordination.beforeBlockInventoryMutation((BlockEntity) (Object) this);
    }
}
