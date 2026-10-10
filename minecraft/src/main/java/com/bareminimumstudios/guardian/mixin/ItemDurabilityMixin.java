package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

@Mixin(ItemStack.class)
public abstract class ItemDurabilityMixin {
    @WrapMethod(method = "hurtAndBreak(ILnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;)V")
    private void guardian$beforeEquippedDamage(int amount, LivingEntity entity, EquipmentSlot slot, Operation<Void> original) {
        if (!MinecraftInventoryCoordination.allowsAutomation(entity.level())) return;
        if (entity instanceof ServerPlayer player) MinecraftInventoryCoordination.beforeEquipmentMutation(player);
        original.call(amount, entity, slot);
    }

    @WrapMethod(method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V")
    private void guardian$beforePlayerDamage(int amount, ServerLevel level, ServerPlayer player, Consumer<Item> onBreak, Operation<Void> original) {
        if (!MinecraftInventoryCoordination.allowsAutomation(level)) return;
        if (player != null) MinecraftInventoryCoordination.beforeEquipmentMutation(player);
        original.call(amount, level, player, onBreak);
    }
}
