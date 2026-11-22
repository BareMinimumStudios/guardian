package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.EquipmentMutationGate;
import com.bareminimumstudios.guardian.platform.minecraft.OwnedEquipmentProvider;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Optional;

@Pseudo
@Mixin(targets = "io.wispforest.accessories.impl.AccessoriesCapabilityImpl", remap = false)
public abstract class AccessoriesCapabilityOwnershipMixin implements OwnedEquipmentProvider {
    @Shadow @Final private LivingEntity entity;

    @Inject(method = {"reset", "updateContainers", "addTransientSlotModifiers", "addPersistentSlotModifiers", "removeSlotModifiers", "clearSlotModifiers", "clearCachedSlotModifiers", "read"}, at = @At("HEAD"), cancellable = true)
    private void guardian$holdNativeTopology(CallbackInfo callback) {
        if (EquipmentMutationGate.blocked(entity)) callback.cancel();
    }

    @Inject(method = "setStack", at = @At("HEAD"), cancellable = true)
    private void guardian$holdNativeEquip(CallbackInfoReturnable<Optional<?>> callback) {
        if (EquipmentMutationGate.blocked(entity)) callback.setReturnValue(Optional.empty());
    }
}
