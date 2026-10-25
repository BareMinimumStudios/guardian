package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.config.GuardianConfig
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.logging.BufferedLogPipeline
import com.bareminimumstudios.guardian.logging.SubmissionResult
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.entity.LecternBlockEntity
import org.slf4j.LoggerFactory

/** Accepted native results only. Books are immutable payloads, never rendered or replayed. */
object LecternHistoryCapture {
    private var pipeline:()->BufferedLogPipeline?={null}
    private var config:()->GuardianConfig?={null}
    private var lastWarning=0L
    class Pending internal constructor(internal val lectern:LecternBlockEntity,internal val before:BlockStateSnapshot,internal val menu:net.minecraft.world.inventory.AbstractContainerMenu)
    fun install(writer:()->BufferedLogPipeline?,settings:()->GuardianConfig?) { pipeline=writer;config=settings }
    private fun enabled():Boolean { val c=config() ?: return false;return c.general.enabled.get() && c.logging.enabled.get() && c.logging.lecternInteractions.get() }
    @JvmStatic fun before(player:ServerPlayer,pos:net.minecraft.core.BlockPos):Pending? {
        val level=player.serverLevel()
        if(!enabled() || !player.server.isSameThread || !level.hasChunk(pos.x shr 4,pos.z shr 4))return null
        val lectern=level.getBlockEntity(pos) as? LecternBlockEntity ?: return null
        return runCatching {
            val state=MinecraftBlockSnapshotter.snapshot(level,pos)
            require((state.blockEntityData?.size ?: 0)<=1_048_576)
            Pending(lectern,state,player.containerMenu)
        }.onFailure(::warn).getOrNull()
    }
    @JvmStatic fun after(player:ServerPlayer,pending:Pending?,accepted:Boolean,allowOpen:Boolean) {
        if(pending==null || !accepted || !enabled() || !player.server.isSameThread)return
        val level=player.serverLevel();val block=pending.lectern;val pos=block.blockPos
        if(block.level!==level || !level.hasChunk(pos.x shr 4,pos.z shr 4) || level.getBlockEntity(pos)!==block)return
        runCatching {
            val after=MinecraftBlockSnapshotter.snapshot(level,pos)
            require(after.blockId==pending.before.blockId && (after.blockEntityData?.size ?: 0)<=1_048_576)
            val opened=allowOpen && player.containerMenu!==pending.menu && (player.containerMenu as? LecternMenuOwner)?.guardianLectern()===block
            if(after==pending.before && !opened)return@runCatching
            if(pipeline()?.submit(BlockChangeSnapshot(System.currentTimeMillis(),MinecraftActorAdapter.player(player),
                ResourceId.parse(level.dimension().location().toString()),BlockPosition(pos.x,pos.y,pos.z),
                pending.before,after,ChangeCause.LECTERN,ActionType.BLOCK_CHANGE))!=SubmissionResult.ACCEPTED)warn(null)
        }.onFailure(::warn)
    }
    private fun warn(t:Throwable?) { val now=System.currentTimeMillis();if(now-lastWarning>=10000) {lastWarning=now;LoggerFactory.getLogger("Guardian/Lecterns").warn("Lectern audit capture failed or exceeded its payload bound; history may be incomplete",t)} }
}
