package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

@Mixin(net.minecraft.world.entity.LivingEntity.class)
public abstract class PlayerEquipmentMixin {
    @WrapMethod(method = "setItemSlot")
    private void guardian$beforeEquip(EquipmentSlot slot, ItemStack stack, Operation<Void> original) {
        if ((Object) this instanceof ServerPlayer player) MinecraftInventoryCoordination.beforeEquipmentMutation(player);
        original.call(slot, stack);
    }

    @WrapMethod(method = "applyItemBlocking")
    private float guardian$beforeShieldDamage(net.minecraft.server.level.ServerLevel level, net.minecraft.world.damagesource.DamageSource source, float amount, Operation<Float> original) {
        if ((Object) this instanceof ServerPlayer player) MinecraftInventoryCoordination.beforeEquipmentMutation(player);
        return original.call(level,source,amount);
    }
}
