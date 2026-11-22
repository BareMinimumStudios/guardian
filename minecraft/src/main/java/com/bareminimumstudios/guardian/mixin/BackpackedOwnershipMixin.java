package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.EquipmentMutationGate;
import com.bareminimumstudios.guardian.platform.minecraft.OwnedBackpackedPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.stream.Stream;

@Mixin(value = Player.class, priority = 900)
public abstract class BackpackedOwnershipMixin implements OwnedBackpackedPlayer {
    @Inject(method = "backpacked$GetBackpackInventory", at = @At("HEAD"), cancellable = true, remap = false)
    private void guardian$holdBackpackCache(CallbackInfoReturnable<Object> callback) {
        if (EquipmentMutationGate.blocked(this)) callback.setReturnValue(null);
    }

    @Inject(method = "backpacked$GetBackpackInventoryCount", at = @At("HEAD"), cancellable = true, remap = false)
    private void guardian$holdBackpackCount(CallbackInfoReturnable<Integer> callback) {
        if (EquipmentMutationGate.blocked(this)) callback.setReturnValue(0);
    }

    @Inject(method = "backpacked$streamNonNullBackpackInventories", at = @At("HEAD"), cancellable = true, remap = false)
    private void guardian$holdBackpackStream(CallbackInfoReturnable<Stream<?>> callback) {
        if (EquipmentMutationGate.blocked(this)) callback.setReturnValue(Stream.empty());
    }
}
