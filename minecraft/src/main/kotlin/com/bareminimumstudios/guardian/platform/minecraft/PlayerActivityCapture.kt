package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.config.GuardianConfig
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.logging.BufferedLogPipeline
import com.bareminimumstudios.guardian.logging.SubmissionResult
import com.bareminimumstudios.guardian.logging.CommandAuditPolicy
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory

object PlayerActivityCapture {
    private var pipeline: () -> BufferedLogPipeline? = { null }
    private var config: () -> GuardianConfig? = { null }
    fun install(writer: () -> BufferedLogPipeline?, settings: () -> GuardianConfig) { pipeline = writer; config = settings }
    @JvmStatic fun capture(player: ServerPlayer, kind: PlayerActivityKind, text: String? = null) {
        val settings = config() ?: return
        if (!settings.general.enabled.get() || !settings.logging.enabled.get()) return
        when(kind) {
            PlayerActivityKind.CHAT -> if(!settings.activity.chat.get())return
            PlayerActivityKind.COMMAND -> if(!settings.activity.commands.get())return
            else -> if(!settings.activity.sessions.get())return
        }
        val capturedText=when(kind) {
            PlayerActivityKind.CHAT -> text?.take(4096)
            PlayerActivityKind.COMMAND -> CommandAuditPolicy.snapshot(text ?: return,settings.activity.commandArguments.get(),settings.activity.commandExcludedRoots.get()) ?: return
            else -> null
        }
        val writer = pipeline() ?: return
        runCatching {
            val entry = PlayerActivity(timestampEpochMillis = System.currentTimeMillis(), actor = ActorIdentity.Player(player.uuid, player.gameProfile.name), kind = kind,
                text = capturedText)
            if (writer.submit(entry) != SubmissionResult.ACCEPTED) LoggerFactory.getLogger("Guardian/Activity").warn("Player activity capture rejected by audit writer")
        }.onFailure { LoggerFactory.getLogger("Guardian/Activity").warn("Could not capture player activity", it) }
    }
}
