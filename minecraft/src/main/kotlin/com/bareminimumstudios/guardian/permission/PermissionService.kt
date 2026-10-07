package com.bareminimumstudios.guardian.permission

import net.minecraft.commands.CommandSourceStack

interface PermissionService {
    val providerName: String

    fun has(
        source: CommandSourceStack,
        permission: String,
        fallbackOperatorLevel: Int
    ): Boolean
}
