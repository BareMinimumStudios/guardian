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
    data class Appearance(val waxed:Boolean,val frontColor:String,val frontGlow:Boolean,val backColor:String,val backGlow:Boolean)
    class InteractionPending internal constructor(internal val sign:SignBlockEntity,internal val before:BlockStateSnapshot,internal val appearance:Appearance)
    private fun appearance(sign:SignBlockEntity)=Appearance(sign.isWaxed,sign.frontText.color.name,sign.frontText.hasGlowingText(),sign.backText.color.name,sign.backText.hasGlowingText())

    /** Block use has already passed claim checks before it can change the sign. Compare the result, not the held item. */
    @JvmStatic fun beginInteraction(world:net.minecraft.world.level.Level,player:Player,pos:net.minecraft.core.BlockPos):InteractionPending? {
        val level=world as? ServerLevel ?: return null
        if(!enabled() || player !is ServerPlayer || !level.server.isSameThread || player.level()!==level || !level.hasChunk(pos.x shr 4,pos.z shr 4))return null
        val sign=level.getBlockEntity(pos) as? SignBlockEntity ?: return null
        return runCatching {
            val before=MinecraftBlockSnapshotter.snapshot(level,pos)
            require((before.blockEntityData?.size ?: 0)<=1_048_576)
            InteractionPending(sign,before,appearance(sign))
        }.onFailure(::warn).getOrNull()
    }
    @JvmStatic fun finishInteraction(world:net.minecraft.world.level.Level,player:Player,pending:InteractionPending?,accepted:Boolean) {
        if(pending==null || !accepted || !enabled() || player !is ServerPlayer)return
        val level=world as? ServerLevel ?: return
        val sign=pending.sign;val p=sign.blockPos
        if(!level.server.isSameThread || player.level()!==level || !level.hasChunk(p.x shr 4,p.z shr 4) || level.getBlockEntity(p)!==sign)return
        runCatching {
            if(appearance(sign)==pending.appearance)return@runCatching
            val after=MinecraftBlockSnapshotter.snapshot(level,p)
            require(after.blockId==pending.before.blockId && (after.blockEntityData?.size ?: 0)<=1_048_576)
            val row=BlockChangeSnapshot(System.currentTimeMillis(),MinecraftActorAdapter.player(player),ResourceId.parse(level.dimension().location().toString()),BlockPosition(p.x,p.y,p.z),pending.before,after,ChangeCause.SIGN_TEXT,ActionType.BLOCK_CHANGE)
            if(pipeline()?.submit(row)!=SubmissionResult.ACCEPTED)warn(null)
        }.onFailure(::warn)
    }
    class CustomPending internal constructor(internal val block:net.minecraft.world.level.block.entity.BlockEntity,internal val before:BlockStateSnapshot)
    @JvmStatic fun beginCustom(player:ServerPlayer,pos:net.minecraft.core.BlockPos):CustomPending? {
        val level=player.serverLevel()
        if(!enabled() || !level.server.isSameThread || !level.hasChunk(pos.x shr 4,pos.z shr 4))return null
        val block=level.getBlockEntity(pos) ?: return null
        if(block.javaClass.name !in setOf("net.mehvahdjukaar.supplementaries.common.block.tiles.SignPostBlockTile","net.mehvahdjukaar.supplementaries.common.block.tiles.NoticeBoardBlockTile"))return null
        return runCatching {
            val before=MinecraftBlockSnapshotter.snapshot(level,pos)
            require((before.blockEntityData?.size ?: 0)<=1_048_576)
            com.bareminimumstudios.guardian.lookup.SignHistoryDetails.changed(before,before)
            CustomPending(block,before)
        }.onFailure(::warn).getOrNull()
    }
    @JvmStatic fun finishCustom(player:ServerPlayer,pending:CustomPending?,accepted:Boolean) {
        if(pending==null || !accepted || !enabled())return
        val level=player.serverLevel();val block=pending.block;val p=block.blockPos
        if(!level.server.isSameThread || block.level!==level || !level.hasChunk(p.x shr 4,p.z shr 4) || level.getBlockEntity(p)!==block)return
        runCatching {
            val after=MinecraftBlockSnapshotter.snapshot(level,p)
            require(after.blockId==pending.before.blockId && (after.blockEntityData?.size ?: 0)<=1_048_576)
            if(!com.bareminimumstudios.guardian.lookup.SignHistoryDetails.changed(pending.before,after))return@runCatching
            val row=BlockChangeSnapshot(System.currentTimeMillis(),MinecraftActorAdapter.player(player),ResourceId.parse(level.dimension().location().toString()),BlockPosition(p.x,p.y,p.z),pending.before,after,ChangeCause.SIGN_TEXT,ActionType.BLOCK_CHANGE)
            if(pipeline()?.submit(row)!=SubmissionResult.ACCEPTED)warn(null)
        }.onFailure(::warn)
    }
    private fun warn(t:Throwable?) { val now=System.currentTimeMillis();if(now-lastWarning>=10000){lastWarning=now;LoggerFactory.getLogger("Guardian/Signs").warn("Sign audit capture failed, exceeded its text/payload bound or rejected a queue entry; history may be incomplete",t)} }
}
