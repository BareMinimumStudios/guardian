package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.PlayerContainerCapture;
import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import org.spongepowered.asm.mixin.injection.At;

/** The accepted menu invocation is scheduled on the server thread. Wrappers can chain with other mods. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ContainerClickMixin {
    @Shadow public ServerPlayer player;

    @WrapMethod(method = "handleContainerClick")
    private void guardian$guardClick(ServerboundContainerClickPacket packet, Operation<Void> original) {
        // Preserve vanilla scheduling; reject on the server thread before accepting prediction.
        if (!player.server.isSameThread()) { original.call(packet); return; }
        if (player.containerMenu.containerId == packet.getContainerId()
                && !MinecraftInventoryCoordination.allowsMenuMutation(player, player.containerMenu)) {
            MinecraftInventoryCoordination.resynchronize(player);
            return;
        }
        original.call(packet);
    }

    @WrapOperation(method = "handleContainerClick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;clicked(IILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)V"))
    private void guardian$captureClick(AbstractContainerMenu menu, int slot, int button, ClickType type, Player player, Operation<Void> original) {
        try (var pending = PlayerContainerCapture.begin(menu, slot, type, player)) {
            original.call(menu, slot, button, type, player);
            PlayerContainerCapture.finish(pending, player);
        }
    }
}
