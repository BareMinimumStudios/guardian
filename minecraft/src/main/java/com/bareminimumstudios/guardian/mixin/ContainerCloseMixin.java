package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.PlayerContainerCapture;
import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerPlayer.class)
public abstract class ContainerCloseMixin {
    @WrapMethod(method = "doCloseContainer")
    private void guardian$captureClose(Operation<Void> original) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        MinecraftInventoryCoordination.beforeClose(player);
        try (var pending = PlayerContainerCapture.beginClose(player)) {
            original.call();
            PlayerContainerCapture.finish(pending, player);
        }
    }
}
