package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.EquipmentMutationGate;
import com.bareminimumstudios.guardian.platform.minecraft.OwnedEquipmentProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "top.theillusivec4.curios.common.inventory.CurioStacksHandler", remap = false)
public abstract class CuriosSlotsOwnershipMixin implements OwnedEquipmentProvider {
    @Inject(method = {"grow", "shrink", "addLegacyChange", "deserializeNBT", "applySyncTag", "copyModifiers", "addTransientModifier", "addPermanentModifier", "removeModifier", "flagUpdate", "clearModifiers", "clearCachedModifiers", "update", "resize", "loseStacks", "updateActiveState", "deactivateSlot", "activateSlot"}, at = @At("HEAD"), cancellable = true)
    private void guardian$holdNativeTopology(CallbackInfo callback) {
        if (EquipmentMutationGate.blocked(this)) callback.cancel();
    }
}
