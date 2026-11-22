package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.EquipmentMutationGate;
import com.bareminimumstudios.guardian.platform.minecraft.OwnedEquipmentProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "io.wispforest.accessories.impl.AccessoriesContainerImpl", remap = false)
public abstract class AccessoriesSlotsOwnershipMixin implements OwnedEquipmentProvider {
    @Inject(method = {"update", "markChanged", "read", "copyFrom", "addTransientModifier", "addPersistentModifier", "removeModifier", "clearModifiers", "removeCachedModifiers", "clearCachedModifiers"}, at = @At("HEAD"), cancellable = true)
    private void guardian$holdNativeTopology(CallbackInfo callback) {
        if (EquipmentMutationGate.blocked(this)) callback.cancel();
    }
}
