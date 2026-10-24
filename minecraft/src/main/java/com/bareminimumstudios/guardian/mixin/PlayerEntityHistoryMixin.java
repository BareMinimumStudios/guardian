package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.EntityHistoryCapture;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class PlayerEntityHistoryMixin {
    // ServerPlayer overrides die without calling LivingEntity.die.
    @Inject(method = "die", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;setLastDeathLocation(Ljava/util/Optional;)V", shift = At.Shift.AFTER), require = 1)
    private void guardian$death(DamageSource source, CallbackInfo ci) {
        EntityHistoryCapture.died((ServerPlayer)(Object)this, source);
    }
}
