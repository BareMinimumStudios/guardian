package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.ActorIdentity
import net.minecraft.world.entity.player.Player

object MinecraftActorAdapter {
    fun player(player: Player): ActorIdentity.Player =
        ActorIdentity.Player(player.uuid, player.gameProfile.name)
}
