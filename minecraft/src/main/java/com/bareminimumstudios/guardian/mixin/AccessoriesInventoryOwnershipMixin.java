package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.EquipmentMutationGate;
import com.bareminimumstudios.guardian.platform.minecraft.MinecraftOwnedItemStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.bareminimumstudios.guardian.platform.minecraft.OwnedEquipmentProvider;
import net.minecraft.world.item.ItemStack;

@Pseudo
@Mixin(targets = "io.wispforest.accessories.impl.ExpandedSimpleContainer", remap = false)
public abstract class AccessoriesInventoryOwnershipMixin implements OwnedEquipmentProvider {
    @Inject(method = {"removeItem", "method_5434", "removeItemNoUpdate", "method_5441"}, at = @At("HEAD"), cancellable = true)
    private void guardian$beforeRemoval(CallbackInfoReturnable<ItemStack> callback) {
        if (EquipmentMutationGate.blocked(this)) callback.setReturnValue(ItemStack.EMPTY);
    }

    @Inject(method = {"setItem", "method_5447"}, at = @At("HEAD"), cancellable = true)
    private void guardian$beforeSetter(CallbackInfo callback) {
        if (!MinecraftOwnedItemStorage.allowsNativeSetter(((OwnedSimpleInventoryAccessor) this).guardian$getItems())) callback.cancel();
    }

    @Inject(method = {"fromTag", "method_7659", "setFromPrev", "copyPrev"}, at = @At("HEAD"), cancellable = true)
    private void guardian$beforeLoading(CallbackInfo callback) {
        if (EquipmentMutationGate.blocked(this)) callback.cancel();
    }
}
