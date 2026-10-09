package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.function.Predicate;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Inventory.class)
public abstract class InventoryMutationMixin {
    @Shadow @Final public Player player;

    @Inject(method = "setItem", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeSlotWrite(int slot, ItemStack stack, CallbackInfo callback) {
        if (!MinecraftInventoryCoordination.allowsReservedPlayerSlotWrite((Inventory) (Object) this, slot, stack)) callback.cancel();
    }

    @Inject(method = {"load", "dropAll", "replaceWith", "clearContent", "placeItemBackInInventory(Lnet/minecraft/world/item/ItemStack;)V", "placeItemBackInInventory(Lnet/minecraft/world/item/ItemStack;Z)V"}, at = @At("HEAD"))
    private void guardian$beforeVoidMutation(CallbackInfo callback) {
        MinecraftInventoryCoordination.beforeInventoryMutation(player);
    }

    @Inject(method = {"setPickedItem", "pickSlot"}, at = @At("HEAD"), cancellable = true)
    private void guardian$beforeHotbarMutation(CallbackInfo callback) {
        if (!MinecraftInventoryCoordination.allowsInventoryMutation(player)) {
            callback.cancel();
            return;
        }
        MinecraftInventoryCoordination.beforeInventoryMutation(player);
    }

    @Inject(method = "removeItem(Lnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeStackRemoval(CallbackInfo callback) {
        if (!MinecraftInventoryCoordination.allowsInventoryMutation(player)) {
            callback.cancel();
            return;
        }
        MinecraftInventoryCoordination.beforeInventoryMutation(player);
    }

    @Inject(method = {"add(Lnet/minecraft/world/item/ItemStack;)Z", "add(ILnet/minecraft/world/item/ItemStack;)Z"}, at = @At("HEAD"))
    private void guardian$beforeAdd(CallbackInfoReturnable<Boolean> callback) {
        MinecraftInventoryCoordination.beforeInventoryMutation(player);
    }

    @Inject(method = {"removeItem(II)Lnet/minecraft/world/item/ItemStack;", "removeItemNoUpdate", "removeFromSelected"}, at = @At("HEAD"), cancellable = true)
    private void guardian$beforeRemoval(CallbackInfoReturnable<ItemStack> callback) {
        if (!MinecraftInventoryCoordination.allowsInventoryMutation(player)) {
            callback.setReturnValue(ItemStack.EMPTY);
            return;
        }
        MinecraftInventoryCoordination.beforeInventoryMutation(player);
    }

    @WrapMethod(method = "clearOrCountMatchingItems")
    private int guardian$beforeBulkClear(Predicate<ItemStack> predicate, int limit, Container extra, Operation<Integer> original) {
        // Even count-only calls evaluate unknown predicates and can touch the cursor.
        // Refuse before entering the composite operation while journal protection is pending.
        if (!MinecraftInventoryCoordination.allowsInventoryMutation(player)) return 0;
        if (limit != 0) MinecraftInventoryCoordination.beforeBulkInventoryMutation(player);
        return original.call(predicate, limit, extra);
    }

    @WrapOperation(method = "replaceWith", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;setItem(ILnet/minecraft/world/item/ItemStack;)V"))
    private void guardian$setCopiedSlot(Inventory target, int slot, ItemStack stack, Operation<Void> original) {
        MinecraftInventoryCoordination.setLifecycleCopiedSlot(target, slot, stack, () -> original.call(target, slot, stack));
    }

}
