package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.EquipmentMutationGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Desc;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.mrcrayfish.framework.entity.sync.DataEntry", remap = false)
public abstract class FrameworkEntryOwnershipMixin {
    @Inject(method = "setValue", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeReplacement(Object value, CallbackInfo callback) {
        if (!EquipmentMutationGate.allowsReplacement(this, value)) callback.cancel();
    }

    @Inject(method = "readValue", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeLoading(CallbackInfo callback) {
        if (EquipmentMutationGate.blocked(this)) callback.cancel();
    }
    // The class also has static read(networkBuffer). Match the instance ValueInput overload by type.
    @Inject(target = @Desc(value = "read", args = ValueInput.class), at = @At("HEAD"), cancellable = true)
    private void guardian$beforeValueLoading(CallbackInfo callback) {
        if (EquipmentMutationGate.blocked(this)) callback.cancel();
    }

}
