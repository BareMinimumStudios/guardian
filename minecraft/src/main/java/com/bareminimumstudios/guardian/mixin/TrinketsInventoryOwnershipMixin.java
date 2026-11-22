package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.EquipmentMutationGate;
import com.bareminimumstudios.guardian.platform.minecraft.MinecraftOwnedItemStorage;
import com.bareminimumstudios.guardian.platform.minecraft.OwnedEquipmentProvider;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.emi.trinkets.api.TrinketInventory", remap = false)
public abstract class TrinketsInventoryOwnershipMixin implements OwnedEquipmentProvider {
    @Shadow private NonNullList<ItemStack> stacks;

    @Inject(method = {"update", "markUpdate", "addModifier", "addPersistentModifier", "removeModifier", "clearModifiers",
            "removeCachedModifier", "clearCachedModifiers", "fromTag", "applySyncTag"}, at = @At("HEAD"), cancellable = true)
    private void guardian$holdNativeTopology(CallbackInfo callback) {
        if (EquipmentMutationGate.blocked(this)) callback.cancel();
    }

    // The optional mod is distributed in intermediary names and is remapped in development runs.
    @Inject(method = {"removeItem", "method_5434", "removeItemNoUpdate", "method_5441"}, at = @At("HEAD"), cancellable = true, require = 2)
    private void guardian$beforeRemoval(CallbackInfoReturnable<ItemStack> callback) {
        if (EquipmentMutationGate.blocked(this)) callback.setReturnValue(ItemStack.EMPTY);
    }

    @Inject(method = {"setItem", "method_5447"}, at = @At("HEAD"), cancellable = true, require = 1)
    private void guardian$beforeSetter(CallbackInfo callback) {
        if (stacks != null && !MinecraftOwnedItemStorage.allowsNativeSetter(stacks)) callback.cancel();
    }

    @Inject(method = {"clearContent", "method_5448"}, at = @At("HEAD"), cancellable = true, require = 1)
    private void guardian$beforeClear(CallbackInfo callback) {
        if (EquipmentMutationGate.blocked(this)) callback.cancel();
    }

    @Inject(method = "copyFrom(Ldev/emi/trinkets/api/TrinketInventory;)V", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeCopy(@Coerce Object previous, CallbackInfo callback) {
        if (EquipmentMutationGate.blocked(this) || EquipmentMutationGate.blocked(previous)) callback.cancel();
    }
}
