package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.EquipmentMutationGate;
import com.bareminimumstudios.guardian.platform.minecraft.OwnedEquipmentProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.item.ItemStack;

@Pseudo
@Mixin(targets = "dev.gigaherz.toolbelt.slot.BeltAttachment", remap = false)
public abstract class ToolBeltOwnershipMixin implements OwnedEquipmentProvider {
    @Inject(method = {"dropContents", "onWornTick", "deserializeNBT"}, at = @At("HEAD"), cancellable = true)
    private void guardian$holdNativeTopology(CallbackInfo callback) {
        if (EquipmentMutationGate.blocked(this)) callback.cancel();
    }

    @Inject(method = "setContents", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeContents(ItemStack stack, CallbackInfo callback) {
        if (!EquipmentMutationGate.allowsReplacement(this, stack)) callback.cancel();
    }
}
