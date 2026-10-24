package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.EntityHistoryCapture;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityHistoryMixin {
    // After the accepted-death flag: cancelled NeoForge deaths never reach this write.
    @Inject(method = "die", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;dead:Z", opcode = 181, shift = At.Shift.AFTER), require = 1)
    private void guardian$death(DamageSource source, CallbackInfo ci) {
        EntityHistoryCapture.died((LivingEntity)(Object)this, source);
    }
}
