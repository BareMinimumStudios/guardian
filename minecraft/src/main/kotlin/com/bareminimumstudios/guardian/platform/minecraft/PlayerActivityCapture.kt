package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.config.GuardianConfig
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.logging.BufferedLogPipeline
import com.bareminimumstudios.guardian.logging.SubmissionResult
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory

object PlayerActivityCapture {
    private var pipeline: () -> BufferedLogPipeline? = { null }
    private var config: () -> GuardianConfig? = { null }
    fun install(writer: () -> BufferedLogPipeline?, settings: () -> GuardianConfig) { pipeline = writer; config = settings }
    @JvmStatic fun capture(player: ServerPlayer, kind: PlayerActivityKind, text: String? = null) {
        val settings = config() ?: return
        if (!settings.general.enabled.get() || !settings.logging.enabled.get()) return
        if (kind == PlayerActivityKind.CHAT && !settings.activity.chat.get()) return
        if (kind != PlayerActivityKind.CHAT && !settings.activity.sessions.get()) return
        val writer = pipeline() ?: return
        runCatching {
            val entry = PlayerActivity(timestampEpochMillis = System.currentTimeMillis(), actor = ActorIdentity.Player(player.uuid, player.gameProfile.name), kind = kind,
                text = if (kind == PlayerActivityKind.CHAT) text?.take(4096) else null)
            if (writer.submit(entry) != SubmissionResult.ACCEPTED) LoggerFactory.getLogger("Guardian/Activity").warn("Player activity capture rejected by audit writer")
        }.onFailure { LoggerFactory.getLogger("Guardian/Activity").warn("Could not capture player activity", it) }
    }
}
