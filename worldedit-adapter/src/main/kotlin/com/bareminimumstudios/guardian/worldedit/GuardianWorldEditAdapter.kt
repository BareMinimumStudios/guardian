package com.bareminimumstudios.guardian.worldedit

import com.bareminimumstudios.guardian.integration.api.GuardianIntegrationApi
import com.sk89q.worldedit.WorldEdit
import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

object GuardianWorldEditAdapter : ModInitializer {
    private val logger = LoggerFactory.getLogger("Guardian/WorldEdit")
    private val selectionProvider = WorldEditSelectionProvider()
    private val listener = WorldEditEditSessionListener()

    override fun onInitialize() {
        GuardianIntegrationApi.registerRegionSelectionProvider(selectionProvider)
        WorldEdit.getInstance().eventBus.register(listener)
        logger.info("Guardian WorldEdit adapter initialized for WorldEdit 7.3.x")
    }
}
