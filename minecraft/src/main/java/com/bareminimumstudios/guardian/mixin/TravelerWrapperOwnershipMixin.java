package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.EquipmentMutationGate;
import com.bareminimumstudios.guardian.platform.minecraft.OwnedEquipmentProvider;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = {"com.tiviacz.travelersbackpack.component.ComponentUtils",
        "com.tiviacz.travelersbackpack.capability.AttachmentUtils",
        "com.tiviacz.travelersbackpack.inventory.BackpackWrapper"}, remap = false)
public abstract class TravelerWrapperOwnershipMixin implements OwnedEquipmentProvider {
    @Inject(method = {"getBackpackWrapper", "getBackpackWrapperArtificial"}, at = @At("HEAD"), cancellable = true)
    private static void guardian$holdWrapperFactory(CallbackInfoReturnable<Object> callback, @Local(argsOnly = true) Player player) {
        if (EquipmentMutationGate.blocked(player)) callback.setReturnValue(null);
    }
}
