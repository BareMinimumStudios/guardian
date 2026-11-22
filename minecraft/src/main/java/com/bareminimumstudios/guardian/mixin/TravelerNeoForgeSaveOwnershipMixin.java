package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.EquipmentSingletonStorage;
import com.bareminimumstudios.guardian.platform.minecraft.OwnedEquipmentSerialization;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.tiviacz.travelersbackpack.capability.TravelersBackpackSerializable", remap = false)
public abstract class TravelerNeoForgeSaveOwnershipMixin implements OwnedEquipmentSerialization {
    @Inject(method = "serializeNBT", at = @At("HEAD"), cancellable = true)
    private void guardian$writeOwnedItem(CallbackInfoReturnable<CompoundTag> callback) {
        CompoundTag owned = EquipmentSingletonStorage.saved(this);
        if (owned != null) callback.setReturnValue(owned);
    }
}
