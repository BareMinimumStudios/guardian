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

import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;

@Mixin(RandomizableContainerBlockEntity.class)
public abstract class RandomizableContainerMutationMixin {
    @Inject(method = "setItem", at = @At("HEAD"))
    private void guardian$beforeSlotWrite(int slot, ItemStack stack, CallbackInfo callback) {
        MinecraftInventoryCoordination.beforeReservedSlotWrite((Container) (Object) this, slot, stack, ReservedSlotEntry.RANDOMIZED);
    }

    @Inject(method = {"setLootTable", "setLootTableSeed"}, at = @At("HEAD"))
    private void guardian$beforeWrite(CallbackInfo callback) {
        MinecraftInventoryCoordination.beforeBlockInventoryMutation((BlockEntity) (Object) this);
    }

    @Inject(method = {"removeItem", "removeItemNoUpdate"}, at = @At("HEAD"))
    private void guardian$beforeRemoval(CallbackInfoReturnable<ItemStack> callback) {
        MinecraftInventoryCoordination.beforeBlockInventoryMutation((BlockEntity) (Object) this);
    }
}
