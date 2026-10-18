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
        com.bareminimumstudios.guardian.platform.minecraft.PlayerActivityCapture.install({ runtime?.pipeline() }, { config })
        NeoForge.EVENT_BUS.addListener(::login)
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
        event.server.playerList.players.forEach { com.bareminimumstudios.guardian.platform.minecraft.PlayerActivityCapture.capture(it, com.bareminimumstudios.guardian.domain.PlayerActivityKind.LOGOUT) }
        BlockInspector.clear()
        if (runtime?.stop() == false) logger.error("Guardian shutdown retained protected item operations or incomplete storage work; check recovery journals and writer errors.")
        runtime = null
    }
    private fun tick(event: ServerTickEvent.Post) { runtime?.tick() }
    private fun commands(event: RegisterCommandsEvent) {
        GuardianCommands.register(event.dispatcher, permissions, { runtime }, { config })
    }
    private fun login(event: PlayerEvent.PlayerLoggedInEvent) {
        (event.entity as? ServerPlayer)?.let { com.bareminimumstudios.guardian.platform.minecraft.PlayerActivityCapture.capture(it, com.bareminimumstudios.guardian.domain.PlayerActivityKind.LOGIN) }
    }
    private fun logout(event: PlayerEvent.PlayerLoggedOutEvent) {
        (event.entity as? ServerPlayer)?.let {
            com.bareminimumstudios.guardian.platform.minecraft.PlayerActivityCapture.capture(it, com.bareminimumstudios.guardian.domain.PlayerActivityKind.LOGOUT)
            BlockInspector.set(it, false)
        }
    }
}
