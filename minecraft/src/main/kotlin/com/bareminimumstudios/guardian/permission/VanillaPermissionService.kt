package com.bareminimumstudios.guardian.permission

import net.minecraft.commands.CommandSourceStack

object VanillaPermissionService : PermissionService {
    override val providerName: String = "vanilla-op-levels"

    override fun has(
        source: CommandSourceStack,
        permission: String,
        fallbackOperatorLevel: Int
    ): Boolean = source.hasPermission(fallbackOperatorLevel)
}
