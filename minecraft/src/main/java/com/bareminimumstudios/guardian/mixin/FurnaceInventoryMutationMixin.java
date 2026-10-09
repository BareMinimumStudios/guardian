package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.bareminimumstudios.guardian.platform.minecraft.ReservedSlotEntry;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class FurnaceInventoryMutationMixin {
    @Inject(method = "setItem", at = @At("HEAD"))
    private void guardian$beforeSlotWrite(int slot, net.minecraft.world.item.ItemStack stack, CallbackInfo callback) {
        MinecraftInventoryCoordination.beforeReservedSlotWrite((Container) (Object) this, slot, stack, ReservedSlotEntry.FURNACE);
    }
}
