package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.bareminimumstudios.guardian.platform.minecraft.ReservedSlotEntry;
import net.minecraft.world.Container;
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
    @Inject(method = "setItem", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeSlotWrite(int slot, ItemStack stack, CallbackInfo callback) {
        if (!MinecraftInventoryCoordination.allowsReservedBlockSlotWrite((Container) (Object) this, slot, stack, ReservedSlotEntry.HOPPER)) callback.cancel();
    }

    @Inject(method = "removeItem", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeRemoval(CallbackInfoReturnable<ItemStack> callback) {
        if (!MinecraftInventoryCoordination.allowsBlockInventoryMutation((BlockEntity) (Object) this)) {
            callback.setReturnValue(ItemStack.EMPTY);
            return;
        }
        MinecraftInventoryCoordination.beforeBlockInventoryMutation((BlockEntity) (Object) this);
    }
}
