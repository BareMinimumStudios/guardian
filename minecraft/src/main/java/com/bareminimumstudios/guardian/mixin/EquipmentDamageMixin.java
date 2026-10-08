package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.damagesource.DamageSource;

@Mixin(LivingEntity.class)
public abstract class EquipmentDamageMixin {
    @WrapMethod(method = "doHurtEquipment")
    private void guardian$beforeArmorDamage(DamageSource source, float amount, EquipmentSlot[] slots, Operation<Void> original) {
        // Revoke before NeoForge armor callbacks as well as actual durability writes.
        if ((Object) this instanceof ServerPlayer player) MinecraftInventoryCoordination.beforeEquipmentMutation(player);
        original.call(source, amount, slots);
    }
}
