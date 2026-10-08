package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.lookup.BlockInspector;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Read-only inspection consumes the scheduled packet before gameplay/protection callbacks. */
@Mixin(value = ServerGamePacketListenerImpl.class, priority = 2000)
public abstract class InspectorPacketMixin {
    @Shadow public ServerPlayer player;
    @Inject(method = "handleUseItemOn", at = @At("HEAD"), cancellable = true)
    private void guardian$inspectUse(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (!BlockInspector.intercept(player, packet.getHitResult().getBlockPos(), true)) return;
        player.connection.ackBlockChangesUpTo(packet.getSequence());
        guardian$update(packet.getHitResult().getBlockPos());
        guardian$update(packet.getHitResult().getBlockPos().relative(packet.getHitResult().getDirection()));
        player.inventoryMenu.sendAllDataToRemote();
        ci.cancel();
    }
    private void guardian$update(net.minecraft.core.BlockPos pos) {
        if (player.serverLevel().getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null)
            player.connection.send(new ClientboundBlockUpdatePacket(player.serverLevel(), pos));
    }
    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void guardian$inspectAttack(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (packet.getAction() != ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) return;
        if (!BlockInspector.intercept(player, packet.getPos(), false)) return;
        player.connection.ackBlockChangesUpTo(packet.getSequence());
        guardian$update(packet.getPos());
        ci.cancel();
    }
}
