package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerPlayer.class)
public abstract class PlayerTransitionMixin {
    @WrapMethod(method = "die")
    private void guardian$beforeDeath(DamageSource source, Operation<Void> original) {
        MinecraftInventoryCoordination.beforePlayerTransition((ServerPlayer) (Object) this);
        original.call(source);
    }

    @WrapMethod(method = "changeDimension")
    private Entity guardian$beforeTravel(DimensionTransition transition, Operation<Entity> original) {
        MinecraftInventoryCoordination.beforePlayerTransition((ServerPlayer) (Object) this);
        return original.call(transition);
    }

    @WrapMethod(method = "restoreFrom")
    private void guardian$beforeCopy(ServerPlayer previous, boolean keepEverything, Operation<Void> original) {
        MinecraftInventoryCoordination.beforePlayerTransition(previous);
        MinecraftInventoryCoordination.beforePlayerTransition((ServerPlayer) (Object) this);
        original.call(previous, keepEverything);
    }
}
