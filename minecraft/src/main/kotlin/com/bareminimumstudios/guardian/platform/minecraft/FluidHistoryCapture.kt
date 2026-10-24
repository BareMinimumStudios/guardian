package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.config.GuardianConfig
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.logging.BufferedLogPipeline
import com.bareminimumstudios.guardian.logging.SubmissionResult
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import org.slf4j.LoggerFactory

object FluidHistoryCapture {
    private var pipeline: () -> BufferedLogPipeline? = { null }
    private var config: () -> GuardianConfig? = { null }
    private var lastWarning=0L
    data class Pending(val level: ServerLevel,val position: BlockPos,val actor: ActorIdentity.Player,val before: FluidSnapshot)
    fun install(writer: () -> BufferedLogPipeline?, settings: () -> GuardianConfig?) { pipeline=writer;config=settings }
    private fun enabled(): Boolean {
        val settings=config() ?: return false
        return settings.general.enabled.get() && settings.logging.enabled.get() && settings.fluids.bucketChanges.get()
    }
    private fun snapshot(level: ServerLevel, position: BlockPos): FluidSnapshot {
        val state=level.getBlockState(position);val fluid=state.fluidState
        return FluidSnapshot(ResourceId.parse(BuiltInRegistries.FLUID.getKey(fluid.type).toString()),fluid.isSource,
            ResourceId.parse(BuiltInRegistries.BLOCK.getKey(state.block).toString()))
    }
    @JvmStatic fun begin(player: Player?,level: Level,position: BlockPos): Pending? {
        if (!enabled() || player !is ServerPlayer || level !is ServerLevel) return null
        return runCatching { Pending(level,position.immutable(),ActorIdentity.Player(player.uuid,player.gameProfile.name),snapshot(level,position)) }.onFailure(::warn).getOrNull()
    }
    @JvmStatic fun finish(pending: Pending?,accepted: Boolean,removal: Boolean) {
        if (pending==null || !accepted || !enabled()) return
        runCatching {
            val after=snapshot(pending.level,pending.position);val before=pending.before
            if (before.fluidId==after.fluidId && before.source==after.source) return
            if (removal && (before.empty || !after.empty) || !removal && after.empty) return
            val pos=pending.position
            val row=FluidAuditEvent(timestampEpochMillis=System.currentTimeMillis(),actor=pending.actor,
                dimension=ResourceId.parse(pending.level.dimension().location().toString()),position=BlockPosition(pos.x,pos.y,pos.z),
                kind=if(removal) FluidAuditKind.REMOVE else FluidAuditKind.PLACE,before=before,after=after)
            val writer=pipeline() ?: return
            if (writer.submit(row)!=SubmissionResult.ACCEPTED) warn(null)
        }.onFailure(::warn)
    }
    private fun warn(failure: Throwable?) {
        val now=System.currentTimeMillis()
        if(now-lastWarning>=10_000) { lastWarning=now;LoggerFactory.getLogger("Guardian/Fluids").warn("Fluid capture failed or its bounded audit queue rejected a record; check Guardian status",failure) }
    }
}
