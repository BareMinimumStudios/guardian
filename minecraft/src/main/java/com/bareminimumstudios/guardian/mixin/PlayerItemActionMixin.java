package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.domain.ContainerAction;
import com.bareminimumstudios.guardian.platform.minecraft.PlayerContainerCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PlayerItemActionMixin {
    @Shadow public ServerPlayer player;
    @WrapMethod(method = "handlePlayerAction")
    private void guardian$captureItemAction(ServerboundPlayerActionPacket packet, Operation<Void> original) {
        // The initial network-thread call must reach vanilla's scheduling guard without capturing.
        if (!player.server.isSameThread()) { original.call(packet); return; }
        ContainerAction action = switch (packet.getAction()) {
            case DROP_ITEM -> ContainerAction.DROP_ONE;
            case DROP_ALL_ITEMS -> ContainerAction.DROP_STACK;
            case SWAP_ITEM_WITH_OFFHAND -> ContainerAction.SWAP_OFFHAND;
            default -> null;
        };
        if (action == null) { original.call(packet); return; }
        try (var pending = PlayerContainerCapture.beginPlayerAction(player, action)) {
            original.call(packet);
            PlayerContainerCapture.finish(pending, player);
        }
    }
}
