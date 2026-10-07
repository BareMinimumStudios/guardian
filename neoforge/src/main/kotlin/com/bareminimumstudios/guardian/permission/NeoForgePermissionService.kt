package com.bareminimumstudios.guardian.permission

import net.minecraft.commands.CommandSourceStack
import net.neoforged.neoforge.server.permission.PermissionAPI
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent
import net.neoforged.neoforge.server.permission.nodes.PermissionNode
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes
import net.neoforged.neoforge.common.NeoForge

/** NeoForge's permission handler can be supplied by LuckPerms; vanilla levels remain the default. */
class NeoForgePermissionService(private val useApi: Boolean) : PermissionService {
    override val providerName = if (useApi) "neoforge-permissions-api" else "vanilla-op-levels"
    private val nodes = mapOf("lookup" to 2, "inspect" to 2, "rollback" to 3, "status" to 2).map { (name, level) ->
        PermissionNode("guardian", name, PermissionTypes.BOOLEAN, { player, _, _ -> player?.createCommandSourceStack()?.hasPermission(level) == true })
    }.associateBy { it.nodeName }

    init {
        if (useApi) NeoForge.EVENT_BUS.addListener { event: PermissionGatherEvent.Nodes -> event.addNodes(*nodes.values.toTypedArray()) }
    }
    override fun has(source: CommandSourceStack, permission: String, fallbackOperatorLevel: Int): Boolean {
        val player = source.player
        val node = nodes[permission]
        return if (useApi && player != null && node != null) PermissionAPI.getPermission(player, node)
        else source.hasPermission(fallbackOperatorLevel)
    }
}
