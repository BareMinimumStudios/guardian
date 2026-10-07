package com.bareminimumstudios.guardian.lookup

import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents

object FabricBlockInspectorHooks {
    fun install() {
        AttackBlockCallback.EVENT.register(AttackBlockCallback { player, world, _, pos, _ ->
            BlockInspector.inspectInteraction(player, world, pos)
        })
        UseBlockCallback.EVENT.register(UseBlockCallback { player, world, _, hit ->
            BlockInspector.inspectInteraction(player, world, hit.blockPos)
        })
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            BlockInspector.set(handler.player, false)
        }
    }
}
