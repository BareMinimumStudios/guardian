package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftOwnedItemStorage;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native handler operations cannot bypass a protected list by returning or merging copies. */
@SuppressWarnings("removal")
@Mixin(value = ItemStackHandler.class, remap = false)
public abstract class ItemStackHandlerOwnershipMixin {
    @Shadow protected NonNullList<ItemStack> stacks;

    @Unique private boolean guardian$owned() {
        return stacks != null && MinecraftOwnedItemStorage.ownsView(stacks);
    }

    @Inject(method = "setStackInSlot", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeSetter(CallbackInfo callback) {
        if (stacks != null && !MinecraftOwnedItemStorage.allowsNativeSetter(stacks)) callback.cancel();
    }

    @Inject(method = "setSize", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeResize(CallbackInfo callback) {
        if (guardian$owned()) callback.cancel();
    }

    @Inject(method = "deserializeNBT", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeLoad(CallbackInfo callback) {
        if (guardian$owned()) callback.cancel();
    }

    @Inject(method = "insertItem", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeInsertion(int slot, ItemStack stack, boolean simulate, CallbackInfoReturnable<ItemStack> callback) {
        if (guardian$owned()) callback.setReturnValue(stack.copy());
    }

    @Inject(method = "extractItem", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeExtraction(CallbackInfoReturnable<ItemStack> callback) {
        if (guardian$owned()) callback.setReturnValue(ItemStack.EMPTY);
    }
}
