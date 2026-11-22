package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.EquipmentMutationGate;
import net.minecraft.core.HolderLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.mrcrayfish.framework.entity.sync.DataHolder", remap = false)
public abstract class FrameworkHolderOwnershipMixin {
    @Inject(method = "deserialize", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeLoading(CallbackInfo callback) {
        if (EquipmentMutationGate.blocked(this)) callback.cancel();
    }

    @Inject(method = "copyInto", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeCopy(@Coerce Object target, HolderLookup.Provider registries, boolean all, CallbackInfo callback) {
        if (EquipmentMutationGate.blocked(this) || EquipmentMutationGate.blocked(target)) callback.cancel();
    }
}
