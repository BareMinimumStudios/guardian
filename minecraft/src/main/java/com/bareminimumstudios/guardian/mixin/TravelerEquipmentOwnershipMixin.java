package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.EquipmentMutationGate;
import com.bareminimumstudios.guardian.platform.minecraft.EquipmentSingletonStorage;
import com.bareminimumstudios.guardian.platform.minecraft.OwnedEquipmentProvider;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = {"com.tiviacz.travelersbackpack.component.TravelersBackpackComponent",
        "com.tiviacz.travelersbackpack.capability.TravelersBackpackSerializable"}, remap = false)
public abstract class TravelerEquipmentOwnershipMixin implements OwnedEquipmentProvider {
    @Inject(method = "getBackpack", at = @At("HEAD"), cancellable = true)
    private void guardian$readOwnedItem(CallbackInfoReturnable<ItemStack> callback) {
        ItemStack owned = EquipmentSingletonStorage.read(this);
        if (owned != null) callback.setReturnValue(owned);
    }
    @Inject(method = "hasBackpack", at = @At("HEAD"), cancellable = true)
    private void guardian$readOwnedPresence(CallbackInfoReturnable<Boolean> callback) {
        ItemStack owned = EquipmentSingletonStorage.read(this);
        if (owned != null) callback.setReturnValue(!owned.isEmpty());
    }
    @Inject(method = "getWrapper", at = @At("HEAD"), cancellable = true)
    private void guardian$holdWrapper(CallbackInfoReturnable<Object> callback) {
        if (EquipmentMutationGate.blocked(this) && !EquipmentSingletonStorage.preparing(this)) callback.setReturnValue(null);
    }
    @Inject(method = {"equipBackpack", "updateBackpack", "applyComponents", "removeWearable", "removeWrapper", "remove",
            "readFromNbt", "deserializeNBT", "applySyncPacket"}, at = @At("HEAD"), cancellable = true)
    private void guardian$holdNativeChanges(CallbackInfo callback) {
        if (EquipmentMutationGate.blocked(this) && !EquipmentSingletonStorage.preparing(this)) callback.cancel();
    }
}
