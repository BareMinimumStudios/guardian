package com.bareminimumstudios.guardian

import com.bareminimumstudios.guardian.command.GuardianCommands
import com.bareminimumstudios.guardian.config.GuardianConfigManager
import com.bareminimumstudios.guardian.integration.FabricIntegrationDetector
import com.bareminimumstudios.guardian.lookup.BlockInspector
import com.bareminimumstudios.guardian.permission.PermissionServices
import com.bareminimumstudios.guardian.platform.minecraft.PlayerBlockCapture
import com.bareminimumstudios.guardian.lookup.FabricBlockInspectorHooks
import com.bareminimumstudios.guardian.platform.minecraft.PlayerBlockBreakHook
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import org.slf4j.LoggerFactory

object Guardian : ModInitializer {
    const val MOD_ID = "guardian"
    private val logger = LoggerFactory.getLogger("Guardian")

    @Volatile
    private var runtime: GuardianRuntime? = null

    override fun onInitialize() {
        GuardianConfigManager.initialize()
        val config = GuardianConfigManager.config

        val permissionService = if (config.integrations.usePermissionApi.get()) {
            PermissionServices.create()
        } else {
            com.bareminimumstudios.guardian.permission.VanillaPermissionService
        }

        if (config.integrations.detectWorldEdit.get() || config.general.verboseStartup.get()) {
            FabricIntegrationDetector.detect().forEach { status ->
                if (config.general.verboseStartup.get() || status.present) {
                    logger.info(
                        "Integration {}: present={}, implemented={}",
                        status.descriptor.displayName,
                        status.present,
                        status.implemented
                    )
                }
            }
        }

        PlayerBlockCapture.install(
            pipelineProvider = { runtime?.pipeline() },
            enabledProvider = {
                config.general.enabled.get() &&
                    config.logging.enabled.get() &&
                    config.logging.playerBlockChanges.get()
            }
        )

        PlayerBlockBreakHook.register()
        FabricBlockInspectorHooks.install()
        BlockInspector.install(
            historyProvider = { runtime?.history() },
            configProvider = { config },
            permissions = permissionService
        )
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            GuardianCommands.register(dispatcher, permissionService, { runtime }, { config })
        }
        ServerTickEvents.END_SERVER_TICK.register { _ -> runtime?.rollback()?.tick(); runtime?.itemRollbackPreview()?.tick() }

        logger.info("Guardian foundation initialized; permission provider={}", permissionService.providerName)

        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            check(runtime == null) { "Guardian runtime already active" }
            runtime = GuardianRuntime(config, permissionService, FabricLoader.getInstance().gameDir.resolve("guardian")).also { it.start(server) }
            val activeStorage = runtime?.storage()
            if (activeStorage != null) {
                val health = activeStorage.health()
                logger.info("Guardian storage ready: backend={}, schema={}", health.backendId, health.schemaVersion)
            if (health.unfinishedItemRollbacks > 0) logger.warn("Guardian has {} unfinished item rollback journals. Automatic item replay is disabled.", health.unfinishedItemRollbacks)
                if (health.uncleanShutdownDetected) {
                    logger.warn(
                        "Guardian detected a previous unclean storage shutdown; integrityCheckPerformed={}, integrityCheckPassed={}",
                        health.integrityCheckPerformed,
                        health.integrityCheckPassed
                    )
                }
                if (activeStorage.id == "memory" && config.storage.warnWhenNonPersistent.get()) {
                    logger.warn("Guardian is using the non-persistent in-memory backend. Audit data will be lost at shutdown.")
                }
            }
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            BlockInspector.clear()
            val clean = runtime?.stop() ?: true
            runtime = null
            if (!clean) {
                logger.error("Guardian audit runtime did not stop cleanly; check writer/bulk/storage errors before shutting down.")
            }
        }
    }
}
