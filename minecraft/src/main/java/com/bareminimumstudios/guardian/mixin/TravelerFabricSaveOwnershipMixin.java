package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.EquipmentSingletonStorage;
import com.bareminimumstudios.guardian.platform.minecraft.OwnedEquipmentSerialization;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.tiviacz.travelersbackpack.component.TravelersBackpackComponent", remap = false)
public abstract class TravelerFabricSaveOwnershipMixin implements OwnedEquipmentSerialization {
    @Inject(method = "writeToNbt", at = @At("HEAD"), cancellable = true)
    private void guardian$writeOwnedItem(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo callback) {
        CompoundTag owned = EquipmentSingletonStorage.saved(this);
        if (owned != null) { tag.put("Wearable", owned); callback.cancel(); }
    }
}
