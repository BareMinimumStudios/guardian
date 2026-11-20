package com.bareminimumstudios.guardian.permission

import net.fabricmc.fabric.api.permission.v1.PermissionContextOwner
import net.minecraft.resources.Identifier
import net.minecraft.server.permissions.PermissionLevel
import net.minecraft.commands.CommandSourceStack

object FabricPermissionsPermissionService : PermissionService {
    override val providerName: String = "fabric-permission-api-v1"

    override fun has(
        source: CommandSourceStack,
        permission: String,
        fallbackOperatorLevel: Int
    ): Boolean = (source as PermissionContextOwner).checkPermission(Identifier.parse(permission.replaceFirst('.', ':')), PermissionLevel.byId(fallbackOperatorLevel))
}
