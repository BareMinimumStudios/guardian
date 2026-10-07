package com.bareminimumstudios.guardian.permission

import net.minecraft.server.command.ServerCommandSource

object VanillaPermissionService : PermissionService {
    override val providerName: String = "vanilla-op-levels"

    override fun has(
        source: ServerCommandSource,
        permission: String,
        fallbackOperatorLevel: Int
    ): Boolean = source.hasPermissionLevel(fallbackOperatorLevel)
}
