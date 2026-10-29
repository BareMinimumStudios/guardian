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
        if(kind==PlayerActivityKind.LOGOUT)ActivitySpy.forget(player.uuid)
        val settings = config() ?: return
        if (!settings.general.enabled.get() || !settings.logging.enabled.get()) return
        when(kind) {
            PlayerActivityKind.CHAT -> if(!settings.activity.chat.get())return
            PlayerActivityKind.COMMAND -> if(!settings.activity.commands.get())return
            PlayerActivityKind.WHISPER -> return
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
            if (writer.submit(entry) == SubmissionResult.ACCEPTED) ActivitySpy.publish(player.server,entry) else LoggerFactory.getLogger("Guardian/Activity").warn("Player activity capture rejected by audit writer")
        }.onFailure { LoggerFactory.getLogger("Guardian/Activity").warn("Could not capture player activity", it) }
    }
    @JvmStatic fun whisper(sender:ServerPlayer,recipient:ServerPlayer,message:net.minecraft.network.chat.PlayerChatMessage,filtered:Boolean) {
        val settings=config() ?: return
        if(!settings.general.enabled.get() || !settings.logging.enabled.get() || !settings.activity.whispers.get() ||
            !sender.server.isSameThread || recipient.server!==sender.server || sender.server.playerList.getPlayer(sender.uuid)!==sender ||
            sender.server.playerList.getPlayer(recipient.uuid)!==recipient || message.isFullyFiltered)return
        val plain=(if(filtered)message.filterMask.apply(message.signedContent()) else message.decoratedContent().string)
            ?.filter { !it.isISOControl() && it.code!=167 }?.take(4096)?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            val row=PlayerActivity(timestampEpochMillis=System.currentTimeMillis(),actor=MinecraftActorAdapter.player(sender),
                kind=PlayerActivityKind.WHISPER,text=plain,recipientUuid=recipient.uuid,recipientName=recipient.gameProfile.name)
            if(pipeline()?.submit(row)==SubmissionResult.ACCEPTED)ActivitySpy.publish(sender.server,row)
            else LoggerFactory.getLogger("Guardian/Activity").warn("Whisper activity capture rejected by audit writer")
        }.onFailure { LoggerFactory.getLogger("Guardian/Activity").warn("Could not capture whisper activity",it) }
    }
}
