package com.bareminimumstudios.guardian.lookup
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
object FabricBlockInspectorHooks {
    fun install() {
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> BlockInspector.set(handler.player, false) }
    }
}
