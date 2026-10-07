package com.bareminimumstudios.guardian.integration

object IntegrationCatalog {
    val WORLD_EDIT = IntegrationDescriptor(
        id = "worldedit",
        modId = "worldedit",
        displayName = "WorldEdit"
    )

    val LUCK_PERMS = IntegrationDescriptor(
        id = "luckperms",
        modId = "luckperms",
        displayName = "LuckPerms"
    )

    val known: List<IntegrationDescriptor> = listOf(WORLD_EDIT, LUCK_PERMS)
}
