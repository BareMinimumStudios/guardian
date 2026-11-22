package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.EquipmentMutationGate;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.mrcrayfish.framework.entity.sync.SyncedEntityData", remap = false)
public abstract class FrameworkPlayerOwnershipMixin {
    @Inject(method = "onPlayerClone", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeClone(Player previous, Player current, boolean respawn, CallbackInfo callback) {
        if (EquipmentMutationGate.blocked(previous) || EquipmentMutationGate.blocked(current)) callback.cancel();
    }
}
