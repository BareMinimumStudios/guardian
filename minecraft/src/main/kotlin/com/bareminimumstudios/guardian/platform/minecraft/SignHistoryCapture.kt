package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.config.GuardianConfig
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.logging.BufferedLogPipeline
import com.bareminimumstudios.guardian.logging.SubmissionResult
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText
import org.slf4j.LoggerFactory

/** Observe the accepted result; never cancel editing or retain a live object on the writer thread. */
object SignHistoryCapture {
    private var pipeline:()->BufferedLogPipeline?={null}
    private var config:()->GuardianConfig?={null}
    private var lastWarning=0L
    class Pending internal constructor(val before:BlockStateSnapshot,internal val text:List<String>,internal val front:Boolean)
    fun install(writer:()->BufferedLogPipeline?,settings:()->GuardianConfig?) { pipeline=writer;config=settings }
    private fun enabled():Boolean { val c=config() ?: return false;return c.general.enabled.get() && c.logging.enabled.get() && c.logging.signTextChanges.get() }
    private fun lines(text:SignText):List<String> = listOf(false,true).flatMap { filtered->(0..3).map { text.getMessage(it,filtered).string.also { line->require(line.length<=1024) { "Sign text exceeds audit limit" } } } }
    @JvmStatic fun before(sign:SignBlockEntity,player:Player,front:Boolean):Pending? {
        val level=sign.level as? ServerLevel ?: return null
        if(!enabled() || player !is ServerPlayer || !level.server.isSameThread || player.level()!==level || sign.isWaxed || sign.playerWhoMayEdit!=player.uuid)return null
        if(!level.hasChunk(sign.blockPos.x shr 4,sign.blockPos.z shr 4) || level.getBlockEntity(sign.blockPos)!==sign)return null
        return runCatching {
            val text=lines(sign.getText(front));val before=MinecraftBlockSnapshotter.snapshot(level,sign.blockPos)
            require((before.blockEntityData?.size ?: 0)<=1_048_576)
            Pending(before,text,front)
        }.onFailure(::warn).getOrNull()
    }
    @JvmStatic fun after(sign:SignBlockEntity,player:Player,pending:Pending?) {
        if(pending==null || !enabled())return
        val level=sign.level as? ServerLevel ?: return
        if(!level.server.isSameThread || !level.hasChunk(sign.blockPos.x shr 4,sign.blockPos.z shr 4) || level.getBlockEntity(sign.blockPos)!==sign)return
        runCatching {
            if(lines(sign.getText(pending.front))==pending.text)return@runCatching
            val after=MinecraftBlockSnapshotter.snapshot(level,sign.blockPos)
            require((after.blockEntityData?.size ?: 0)<=1_048_576)
            val p=sign.blockPos
            val row=BlockChangeSnapshot(System.currentTimeMillis(),MinecraftActorAdapter.player(player),ResourceId.parse(level.dimension().location().toString()),BlockPosition(p.x,p.y,p.z),pending.before,after,ChangeCause.SIGN_TEXT,ActionType.BLOCK_CHANGE)
            if(pipeline()?.submit(row)!=SubmissionResult.ACCEPTED)warn(null)
        }.onFailure(::warn)
    }
    private fun warn(t:Throwable?) { val now=System.currentTimeMillis();if(now-lastWarning>=10000){lastWarning=now;LoggerFactory.getLogger("Guardian/Signs").warn("Sign audit capture failed, exceeded its text/payload bound or rejected a queue entry; history may be incomplete",t)} }
}
