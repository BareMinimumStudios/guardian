package com.bareminimumstudios.guardian.permission

import net.minecraft.server.permissions.Permission
import net.minecraft.server.permissions.PermissionLevel
import net.minecraft.commands.CommandSourceStack

object VanillaPermissionService : PermissionService {
    override val providerName: String = "vanilla-op-levels"

    override fun has(
        source: CommandSourceStack,
        permission: String,
        fallbackOperatorLevel: Int
    ): Boolean = source.permissions().hasPermission(Permission.HasCommandLevel(PermissionLevel.byId(fallbackOperatorLevel)))
}
