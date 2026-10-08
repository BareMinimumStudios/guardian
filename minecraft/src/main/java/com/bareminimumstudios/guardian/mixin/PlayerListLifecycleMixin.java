package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(PlayerList.class)
public abstract class PlayerListLifecycleMixin {
    @WrapMethod(method = "remove")
    private void guardian$beforeDisconnect(ServerPlayer player, Operation<Void> original) {
        MinecraftInventoryCoordination.beforePlayerTransition(player);
        original.call(player);
    }

    @WrapMethod(method = "respawn")
    private ServerPlayer guardian$beforeRespawn(ServerPlayer player, boolean keepEverything, Entity.RemovalReason reason, Operation<ServerPlayer> original) {
        MinecraftInventoryCoordination.beforePlayerTransition(player);
        return original.call(player, keepEverything, reason);
    }
}
