package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.EntityHistoryCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Animal.class)
public abstract class BreedingHistoryMixin {
    @WrapMethod(method = "spawnChildFromBreeding")
    private void guardian$breedingContext(ServerLevel level, Animal partner, Operation<Void> original) {
        EntityHistoryCapture.withBreeding((Animal)(Object)this,partner,()->original.call(level,partner));
    }
    @WrapOperation(method = "spawnChildFromBreeding", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)V"), require = 1)
    private void guardian$born(ServerLevel level, Entity child, Operation<Void> original) {
        original.call(level,child);
        EntityHistoryCapture.bred(level,child);
    }
}
