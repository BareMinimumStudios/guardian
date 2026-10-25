package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.domain.ContainerAction;
import com.bareminimumstudios.guardian.platform.minecraft.PlayerContainerCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemEntity.class)
public abstract class GroundItemPickupMixin {
    @WrapMethod(method="playerTouch")
    private void guardian$capturePickup(Player player, Operation<Void> original) {
        var before=player instanceof ServerPlayer serverPlayer ? PlayerContainerCapture.beginPlayerAction(serverPlayer,ContainerAction.GROUND_PICKUP) : null;
        try {
            original.call(player);
            PlayerContainerCapture.finish(before,player);
        } finally {
            if(before!=null)before.close();
        }
    }
}
