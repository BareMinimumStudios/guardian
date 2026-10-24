package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.config.GuardianConfig
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.logging.BufferedLogPipeline
import com.bareminimumstudios.guardian.logging.SubmissionResult
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.player.Player
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import org.slf4j.LoggerFactory

object EntityHistoryCapture {
    private var pipeline: () -> BufferedLogPipeline? = { null }
    private var config: () -> GuardianConfig? = { null }
    private val renameActor = ThreadLocal<ActorIdentity>()
    private data class BreedingContext(val parent: java.util.UUID, val partner: java.util.UUID, val actor: ActorIdentity)
    private val breedingContext = ThreadLocal<BreedingContext>()
    private var lastWarning = 0L

    fun install(writer: () -> BufferedLogPipeline?, settings: () -> GuardianConfig?) { pipeline = writer; config = settings }
    private fun enabled(kind: EntityAuditKind): Boolean {
        val settings = config() ?: return false
        return settings.general.enabled.get() && settings.logging.enabled.get() &&
            when(kind) {
                EntityAuditKind.DEATH -> settings.entities.deaths.get()
                EntityAuditKind.NAME_CHANGE -> settings.entities.nameChanges.get()
                EntityAuditKind.TAME -> settings.entities.taming.get()
                EntityAuditKind.BREED -> settings.entities.breeding.get()
                EntityAuditKind.FRAME_PLACE, EntityAuditKind.FRAME_REMOVE, EntityAuditKind.FRAME_CHANGE -> settings.entities.itemFrames.get()
            }
    }
    @JvmStatic fun plainName(entity: Entity): String? = entity.customName?.getString(256)?.filter { !it.isISOControl() }
    @JvmStatic fun withRenameActor(player: net.minecraft.world.entity.player.Player, change: Runnable) {
        val previous = renameActor.get()
        renameActor.set(ActorIdentity.Player(player.uuid,player.gameProfile.name))
        try { change.run() } finally { if (previous == null) renameActor.remove() else renameActor.set(previous) }
    }
    @JvmStatic fun renamed(entity: Entity, before: String?) {
        if (!enabled(EntityAuditKind.NAME_CHANGE)) return
        val level = entity.level() as? ServerLevel ?: return
        // NBT loading and construction set names before the entity is registered in its level.
        if (level.getEntity(entity.uuid) !== entity) return
        val after = plainName(entity)
        if (before == after) return
        submit(entity, EntityAuditKind.NAME_CHANGE, renameActor.get() ?: ActorIdentity.Unknown, before, after, null)
    }
    @JvmStatic fun died(entity: Entity, source: DamageSource) {
        if (!enabled(EntityAuditKind.DEATH) || entity.level() !is ServerLevel) return
        val killer = source.entity
        val actor = when(killer) {
            is ServerPlayer -> ActorIdentity.Player(killer.uuid,killer.gameProfile.name)
            null -> ActorIdentity.System("environment")
            else -> ActorIdentity.Entity(killer.uuid,ResourceId.parse(BuiltInRegistries.ENTITY_TYPE.getKey(killer.type).toString()))
        }
        val damage = source.typeHolder().unwrapKey().orElse(null)?.location()?.toString()?.let(ResourceId::parse)
        submit(entity,EntityAuditKind.DEATH,actor,plainName(entity),null,damage)
    }
    @JvmStatic fun tamed(entity: Entity, player: Player) {
        if (!enabled(EntityAuditKind.TAME) || player !is ServerPlayer) return
        val level=entity.level() as? ServerLevel ?: return
        if (level.getEntity(entity.uuid) !== entity) return
        submit(entity,EntityAuditKind.TAME,ActorIdentity.Player(player.uuid,player.gameProfile.name),plainName(entity),null,null)
    }
    @JvmStatic fun withBreeding(parent: Animal, partner: Animal, change: Runnable) {
        if (!enabled(EntityAuditKind.BREED)) { change.run();return }
        val player=parent.loveCause ?: partner.loveCause
        val actor=player?.let { ActorIdentity.Player(it.uuid,it.gameProfile.name) } ?: ActorIdentity.Unknown
        val previous=breedingContext.get()
        breedingContext.set(BreedingContext(parent.uuid,partner.uuid,actor))
        try { change.run() } finally { if(previous==null) breedingContext.remove() else breedingContext.set(previous) }
    }
    @JvmStatic fun bred(level: ServerLevel, child: Entity) {
        if (!enabled(EntityAuditKind.BREED) || level.getEntity(child.uuid) !== child) return
        val context=breedingContext.get() ?: return
        submit(child,EntityAuditKind.BREED,context.actor,plainName(child),null,null,context.parent,context.partner)
    }
    private fun submit(entity: Entity, kind: EntityAuditKind, actor: ActorIdentity, before: String?, after: String?, damage: ResourceId?, parent: java.util.UUID? = null, partner: java.util.UUID? = null) {
        val writer = pipeline() ?: return
        runCatching {
            val pos = entity.blockPosition()
            val row = EntityAuditEvent(timestampEpochMillis=System.currentTimeMillis(),actor=actor,
                dimension=ResourceId.parse(entity.level().dimension().location().toString()),position=BlockPosition(pos.x,pos.y,pos.z),
                targetUuid=entity.uuid,targetType=ResourceId.parse(BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString()),kind=kind,
                beforeName=before,afterName=after,damageType=damage,parentUuid=parent,partnerUuid=partner)
            if (writer.submit(row) != SubmissionResult.ACCEPTED) warn(null)
        }.onFailure(::warn)
    }
    private fun warn(failure: Throwable?) {
        val now = System.currentTimeMillis()
        if (now-lastWarning >= 10_000) {
            lastWarning=now
            LoggerFactory.getLogger("Guardian/Entities").warn("Entity history capture failed or its bounded audit queue rejected a record; check Guardian status",failure)
        }
    }
}
