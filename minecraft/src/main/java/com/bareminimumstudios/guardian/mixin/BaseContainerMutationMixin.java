package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.bareminimumstudios.guardian.platform.minecraft.ReservedSlotEntry;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;

@Mixin(BaseContainerBlockEntity.class)
public abstract class BaseContainerMutationMixin {
    @Inject(method = "setItem", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeSlotWrite(int slot, ItemStack stack, CallbackInfo callback) {
        if (!MinecraftInventoryCoordination.allowsReservedBlockSlotWrite((Container) (Object) this, slot, stack, ReservedSlotEntry.BASE)) callback.cancel();
    }

    @Inject(method = {"clearContent"}, at = @At("HEAD"), cancellable = true)
    private void guardian$beforeWrite(CallbackInfo callback) {
        if (!MinecraftInventoryCoordination.allowsBlockInventoryMutation((BlockEntity) (Object) this)) {
            callback.cancel();
            return;
        }
        MinecraftInventoryCoordination.beforeBlockInventoryMutation((BlockEntity) (Object) this);
    }

    @Inject(method = {"removeItem", "removeItemNoUpdate"}, at = @At("HEAD"), cancellable = true)
    private void guardian$beforeRemoval(CallbackInfoReturnable<ItemStack> callback) {
        if (!MinecraftInventoryCoordination.allowsBlockInventoryMutation((BlockEntity) (Object) this)) {
            callback.setReturnValue(ItemStack.EMPTY);
            return;
        }
        MinecraftInventoryCoordination.beforeBlockInventoryMutation((BlockEntity) (Object) this);
    }
}
