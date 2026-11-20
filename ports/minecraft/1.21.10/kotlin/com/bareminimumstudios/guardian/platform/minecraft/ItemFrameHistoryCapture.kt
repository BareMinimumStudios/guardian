package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.config.GuardianConfig
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.logging.BufferedLogPipeline
import com.bareminimumstudios.guardian.logging.SubmissionResult
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.player.Player
import java.util.function.BooleanSupplier
import org.slf4j.LoggerFactory

object ItemFrameHistoryCapture {
    private var pipeline: () -> BufferedLogPipeline? = { null }
    private var config: () -> GuardianConfig? = { null }
    private val damageActor=ThreadLocal<ActorIdentity>()
    private var lastWarning=0L
    data class Pending(val frame: ItemFrame,val actor: ActorIdentity,val before: ItemFrameSnapshot)
    fun install(writer: () -> BufferedLogPipeline?,settings: () -> GuardianConfig?) { pipeline=writer;config=settings }
    private fun enabled(): Boolean {
        val settings=config() ?: return false
        return settings.general.enabled.get() && settings.logging.enabled.get() && settings.entities.itemFrames.get()
    }
    private fun actor(entity: Entity?): ActorIdentity = when(entity) {
        is ServerPlayer -> ActorIdentity.Player(entity.uuid,entity.gameProfile.name)
        null -> ActorIdentity.System("environment")
        else -> ActorIdentity.Entity(entity.uuid,ResourceId.parse(BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString()))
    }
    private fun snapshot(frame: ItemFrame)=ItemFrameSnapshot(MinecraftItemSnapshotter.capture(frame.item,frame.level().registryAccess()),frame.rotation)
    @JvmStatic fun begin(frame: ItemFrame,player: Player?): Pending? = beginActor(frame,actor(player))
    private fun beginActor(frame: ItemFrame,actor: ActorIdentity): Pending? {
        if(!enabled() || frame.isRemoved) return null
        val level=frame.level() as? ServerLevel ?: return null
        if(level.getEntity(frame.uuid)!==frame) return null
        return runCatching { Pending(frame,actor,snapshot(frame)) }.onFailure(::warn).getOrNull()
    }
    @JvmStatic fun changed(pending: Pending?,accepted: Boolean) {
        if(pending==null || !accepted || !enabled() || pending.frame.isRemoved) return
        runCatching {
            val after=snapshot(pending.frame)
            if(after!=pending.before) submit(pending.frame,pending.actor,EntityAuditKind.FRAME_CHANGE,pending.before,after)
        }.onFailure(::warn)
    }
    @JvmStatic fun placed(frame: ItemFrame,player: Player?) {
        if(player !is ServerPlayer) return
        val pending=begin(frame,player) ?: return
        submit(frame,pending.actor,EntityAuditKind.FRAME_PLACE,null,pending.before)
    }
    @JvmStatic fun hurt(frame: ItemFrame,source: DamageSource,change: BooleanSupplier): Boolean {
        if(!enabled()) return change.asBoolean
        val previous=damageActor.get();val actor=actor(source.entity)
        damageActor.set(actor)
        try {
            val before=beginActor(frame,actor)
            val result=change.asBoolean
            changed(before,result)
            return result
        } finally { if(previous==null) damageActor.remove() else damageActor.set(previous) }
    }
    @JvmStatic fun removed(frame: ItemFrame,reason: Entity.RemovalReason,change: Runnable) {
        if(!reason.shouldDestroy()) { change.run();return }
        val before=beginActor(frame,damageActor.get() ?: ActorIdentity.System("world"))
        change.run()
        if(before!=null && frame.isRemoved && enabled()) submit(frame,before.actor,EntityAuditKind.FRAME_REMOVE,before.before,null)
    }
    private fun submit(frame: ItemFrame,actor: ActorIdentity,kind: EntityAuditKind,before: ItemFrameSnapshot?,after: ItemFrameSnapshot?) {
        val writer=pipeline() ?: return
        runCatching {
            val pos=frame.pos
            val row=EntityAuditEvent(timestampEpochMillis=System.currentTimeMillis(),actor=actor,
                dimension=ResourceId.parse(frame.level().dimension().location().toString()),position=BlockPosition(pos.x,pos.y,pos.z),
                targetUuid=frame.uuid,targetType=ResourceId.parse(BuiltInRegistries.ENTITY_TYPE.getKey(frame.type).toString()),kind=kind,
                beforeName=EntityHistoryCapture.plainName(frame),frameBefore=before,frameAfter=after)
            if(writer.submit(row)!=SubmissionResult.ACCEPTED) warn(null)
        }.onFailure(::warn)
    }
    private fun warn(failure: Throwable?) {
        val now=System.currentTimeMillis()
        if(now-lastWarning>=10_000) { lastWarning=now;LoggerFactory.getLogger("Guardian/Frames").warn("Frame capture failed or its bounded audit queue rejected a record; check Guardian status",failure) }
    }
}
