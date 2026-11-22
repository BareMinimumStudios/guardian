package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftOwnedItemStorage;
import net.minecraft.core.NonNullList;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

/** Protect enclosing operations too: removeItemType can otherwise extract a returned copy. */
@Mixin(SimpleContainer.class)
public abstract class SimpleContainerOwnershipMixin {
    @Unique private NonNullList<ItemStack> guardian$ownedItems() {
        return ((OwnedSimpleInventoryAccessor) this).guardian$getItems();
    }

    @Inject(method = "setItem", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeSetter(CallbackInfo callback) {
        if (!MinecraftOwnedItemStorage.allowsNativeSetter(guardian$ownedItems())) callback.cancel();
    }

    @Inject(method = {"removeItem", "removeItemNoUpdate", "removeItemType"}, at = @At("HEAD"), cancellable = true)
    private void guardian$beforeRemoval(CallbackInfoReturnable<ItemStack> callback) {
        if (MinecraftOwnedItemStorage.ownsView(guardian$ownedItems())) callback.setReturnValue(ItemStack.EMPTY);
    }

    @Inject(method = "removeAllItems", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeRemoveAll(CallbackInfoReturnable<List<ItemStack>> callback) {
        if (MinecraftOwnedItemStorage.ownsView(guardian$ownedItems())) callback.setReturnValue(List.of());
    }

    @Inject(method = "clearContent", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeClear(CallbackInfo callback) {
        if (MinecraftOwnedItemStorage.ownsView(guardian$ownedItems())) callback.cancel();
    }

    @Inject(method = "addItem", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeAddition(ItemStack stack, CallbackInfoReturnable<ItemStack> callback) {
        if (MinecraftOwnedItemStorage.ownsView(guardian$ownedItems())) callback.setReturnValue(stack.copy());
    }

    @Inject(method = "canAddItem", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeSpaceCheck(CallbackInfoReturnable<Boolean> callback) {
        if (MinecraftOwnedItemStorage.ownsView(guardian$ownedItems())) callback.setReturnValue(false);
    }
}
