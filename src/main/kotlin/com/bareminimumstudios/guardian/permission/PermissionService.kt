package com.bareminimumstudios.guardian.permission

import net.minecraft.server.command.ServerCommandSource

interface PermissionService {
    val providerName: String

    fun has(
        source: ServerCommandSource,
        permission: String,
        fallbackOperatorLevel: Int
    ): Boolean
}
