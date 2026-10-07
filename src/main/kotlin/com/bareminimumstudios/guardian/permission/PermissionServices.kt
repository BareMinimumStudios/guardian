package com.bareminimumstudios.guardian.permission

import net.fabricmc.loader.api.FabricLoader

object PermissionServices {
    fun create(): PermissionService {
        val loader = FabricLoader.getInstance()
        return if (loader.isModLoaded("fabric-permissions-api-v0")) {
            FabricPermissionsPermissionService
        } else {
            VanillaPermissionService
        }
    }
}
