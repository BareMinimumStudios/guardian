package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HopperBlockEntity.class)
public abstract class HopperInventoryMutationMixin {
    @Inject(method = "setItem", at = @At("HEAD"))
    private void guardian$beforeWrite(CallbackInfo callback) {
        MinecraftInventoryCoordination.beforeBlockInventoryMutation((BlockEntity) (Object) this);
    }

    @Inject(method = "removeItem", at = @At("HEAD"))
    private void guardian$beforeRemoval(CallbackInfoReturnable<ItemStack> callback) {
        MinecraftInventoryCoordination.beforeBlockInventoryMutation((BlockEntity) (Object) this);
    }
}
