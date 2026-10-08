package com.bareminimumstudios.guardian

import com.bareminimumstudios.guardian.command.GuardianCommands
import com.bareminimumstudios.guardian.config.GuardianConfigManager
import com.bareminimumstudios.guardian.lookup.BlockInspector
import com.bareminimumstudios.guardian.permission.NeoForgePermissionService
import com.bareminimumstudios.guardian.platform.minecraft.PlayerBlockCapture
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStartingEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory

@Mod(value = "guardian", dist = [Dist.DEDICATED_SERVER])
class GuardianNeoForge {
    private val logger = LoggerFactory.getLogger("Guardian")
    private val config = GuardianConfigManager.config
    private val permissions = NeoForgePermissionService(config.integrations.usePermissionApi.get())
    @Volatile private var runtime: GuardianRuntime? = null

    init {
        PlayerBlockCapture.install(
            { runtime?.pipeline() },
            { config.general.enabled.get() && config.logging.enabled.get() && config.logging.playerBlockChanges.get() }
        )
        BlockInspector.install({ runtime?.history() }, { config }, permissions)
        NeoForge.EVENT_BUS.addListener(::starting)
        NeoForge.EVENT_BUS.addListener(::stopping)
        NeoForge.EVENT_BUS.addListener(::tick)
        NeoForge.EVENT_BUS.addListener(::commands)
        NeoForge.EVENT_BUS.addListener(::logout)
        logger.info("Guardian NeoForge initialized; permission provider={}", permissions.providerName)
    }

    private fun starting(event: ServerStartingEvent) {
        check(runtime == null)
        runtime = GuardianRuntime(config, permissions, FMLPaths.GAMEDIR.get().resolve("guardian")).also { it.start(event.server) }
        runtime?.storage()?.health()?.let {
            logger.info("Guardian storage ready: backend={}, schema={}", it.backendId, it.schemaVersion)
            if (it.unfinishedItemRollbacks > 0) logger.warn("Guardian has {} unfinished item rollback journals. Automatic item replay is disabled.", it.unfinishedItemRollbacks)
            if (it.uncleanShutdownDetected) logger.warn("Guardian recovered an unclean storage shutdown; integrity passed={}", it.integrityCheckPassed)
        }
    }
    private fun stopping(event: ServerStoppingEvent) {
        BlockInspector.clear()
        if (runtime?.stop() == false) logger.error("Guardian did not stop cleanly; check writer and storage errors.")
        runtime = null
    }
    private fun tick(event: ServerTickEvent.Post) { runtime?.rollback()?.tick(); runtime?.itemRollbackPreview()?.tick() }
    private fun commands(event: RegisterCommandsEvent) {
        GuardianCommands.register(event.dispatcher, permissions, { runtime }, { config })
    }
    private fun logout(event: PlayerEvent.PlayerLoggedOutEvent) {
        (event.entity as? ServerPlayer)?.let { BlockInspector.set(it, false) }
    }
}
