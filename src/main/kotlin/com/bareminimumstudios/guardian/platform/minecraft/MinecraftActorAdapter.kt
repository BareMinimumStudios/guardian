package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.ActorIdentity
import net.minecraft.entity.player.PlayerEntity

object MinecraftActorAdapter {
    fun player(player: PlayerEntity): ActorIdentity.Player =
        ActorIdentity.Player(player.uuid, player.gameProfile.name)
}
