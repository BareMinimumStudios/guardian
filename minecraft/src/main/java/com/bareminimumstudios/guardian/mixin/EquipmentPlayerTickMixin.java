package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.EquipmentMutationGate;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

/** Defer the entire native/mod player tick while a composite inventory handoff is retained. */
@Mixin(ServerPlayer.class)
public abstract class EquipmentPlayerTickMixin {
    @WrapMethod(method = {"tick", "doTick"})
    private void guardian$holdEquipmentTick(Operation<Void> original) {
        if (!EquipmentMutationGate.blocked(this)) original.call();
    }
}
