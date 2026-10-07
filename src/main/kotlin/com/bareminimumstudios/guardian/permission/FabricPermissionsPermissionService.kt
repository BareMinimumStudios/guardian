package com.bareminimumstudios.guardian.permission

import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.server.command.ServerCommandSource

object FabricPermissionsPermissionService : PermissionService {
    override val providerName: String = "fabric-permissions-api-v0"

    override fun has(
        source: ServerCommandSource,
        permission: String,
        fallbackOperatorLevel: Int
    ): Boolean = Permissions.check(source, permission, fallbackOperatorLevel)
}
