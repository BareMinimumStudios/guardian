package com.bareminimumstudios.guardian.permission

import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.commands.CommandSourceStack

object FabricPermissionsPermissionService : PermissionService {
    override val providerName: String = "fabric-permissions-api-v0"

    override fun has(
        source: CommandSourceStack,
        permission: String,
        fallbackOperatorLevel: Int
    ): Boolean = Permissions.check(source, permission, fallbackOperatorLevel)
}
