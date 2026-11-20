package com.bareminimumstudios.guardian.permission

/** Fabric API supplies the permission context and vanilla operator-level fallback. */
object PermissionServices {
    fun create(): PermissionService = FabricPermissionsPermissionService
}
