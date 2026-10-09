package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.bareminimumstudios.guardian.platform.minecraft.ReservedSlotEntry;
import net.minecraft.world.Container;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
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

    @Inject(method = "setItem", at = @At("HEAD"))
    private void guardian$beforeSlotWrite(int slot, ItemStack stack, CallbackInfo callback) {
        MinecraftInventoryCoordination.beforeReservedSlotWrite((Container) (Object) this, slot, stack, ReservedSlotEntry.PLAYER);
    }

    @Inject(method = {"removeItem(Lnet/minecraft/world/item/ItemStack;)V", "load", "dropAll", "replaceWith", "clearContent", "setPickedItem", "pickSlot", "placeItemBackInInventory(Lnet/minecraft/world/item/ItemStack;)V", "placeItemBackInInventory(Lnet/minecraft/world/item/ItemStack;Z)V"}, at = @At("HEAD"))
    private void guardian$beforeVoidMutation(CallbackInfo callback) {
        MinecraftInventoryCoordination.beforeInventoryMutation(player);
    }

    @Inject(method = {"add(Lnet/minecraft/world/item/ItemStack;)Z", "add(ILnet/minecraft/world/item/ItemStack;)Z"}, at = @At("HEAD"))
    private void guardian$beforeAdd(CallbackInfoReturnable<Boolean> callback) {
        MinecraftInventoryCoordination.beforeInventoryMutation(player);
    }

    @Inject(method = {"removeItem(II)Lnet/minecraft/world/item/ItemStack;", "removeItemNoUpdate", "removeFromSelected"}, at = @At("HEAD"))
    private void guardian$beforeRemoval(CallbackInfoReturnable<ItemStack> callback) {
        MinecraftInventoryCoordination.beforeInventoryMutation(player);
    }

    @WrapMethod(method = "clearOrCountMatchingItems")
    private int guardian$beforeBulkClear(Predicate<ItemStack> predicate, int limit, Container extra, Operation<Integer> original) {
        if (limit != 0) MinecraftInventoryCoordination.beforeBulkInventoryMutation(player);
        return original.call(predicate, limit, extra);
    }
}
