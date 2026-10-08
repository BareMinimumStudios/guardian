package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

@Mixin(ItemStack.class)
public abstract class InventoryItemTickMixin {
    @WrapMethod(method = "inventoryTick")
    private void guardian$guardInventoryTick(Level level, Entity entity, int slot, boolean selected, Operation<Void> original) {
        if (entity instanceof ServerPlayer player && !MinecraftInventoryCoordination.allowsAutomation(player.level())) return;
        original.call(level, entity, slot, selected);
    }
}
