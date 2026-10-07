package com.bareminimumstudios.guardian.integration

import net.fabricmc.loader.api.FabricLoader

object FabricIntegrationDetector {
    fun detect(): List<IntegrationStatus> {
        val loader = FabricLoader.getInstance()
        return IntegrationCatalog.known.map { descriptor ->
            IntegrationStatus(
                descriptor = descriptor,
                present = loader.isModLoaded(descriptor.modId),
                implemented = when (descriptor.id) {
                    "worldedit" -> loader.isModLoaded("guardian-worldedit")
                    "luckperms" -> loader.isModLoaded("fabric-permissions-api-v0") || loader.isModLoaded("luckperms")
                    else -> false
                }
            )
        }
    }
}
