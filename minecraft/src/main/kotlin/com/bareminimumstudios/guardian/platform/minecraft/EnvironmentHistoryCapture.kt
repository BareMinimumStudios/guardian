package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.config.GuardianConfig
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.logging.BufferedLogPipeline
import com.bareminimumstudios.guardian.logging.SubmissionResult
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.state.BlockState
import org.slf4j.LoggerFactory

/** Scoped observation only; it neither cancels gameplay nor loads missing chunks. */
object EnvironmentHistoryCapture {
    private var pipeline:()->BufferedLogPipeline?={null}
    private var config:()->GuardianConfig?={null}
    class Scope internal constructor(val level:ServerLevel,val cause:ChangeCause,val actor:ActorIdentity)
    internal class Entry(val before:BlockStateSnapshot,val cause:ChangeCause,val actor:ActorIdentity,val scoped:Boolean)
    class Pending internal constructor(val level:ServerLevel,internal val entries:LinkedHashMap<BlockPos,Entry>,internal val root:Boolean)
    private val scopes=ThreadLocal.withInitial{java.util.ArrayDeque<Scope>()}
    private val mutations=ThreadLocal.withInitial{java.util.ArrayDeque<Pending>()}
    private var lastWarning=0L
    fun install(writer:()->BufferedLogPipeline?,settings:()->GuardianConfig?) { pipeline=writer;config=settings;scopes.remove();mutations.remove() }
    private fun enabled(cause:ChangeCause):Boolean {
        val c=config() ?: return false
        return c.general.enabled.get() && c.logging.enabled.get() && when(cause) { ChangeCause.FIRE->c.environment.fire.get();ChangeCause.EXPLOSION->c.environment.explosions.get();else->false }
    }
    private fun actor(entity:Entity?):ActorIdentity=when(entity) {
        is Player->MinecraftActorAdapter.player(entity)
        null->ActorIdentity.Unknown
        else->ActorIdentity.Entity(entity.uuid,ResourceId.parse(BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString()))
    }
    @JvmStatic fun enterFire(level:Level,player:Player?):Scope?=enter(level,ChangeCause.FIRE,actor(player))
    @JvmStatic fun enterIgnition(context:UseOnContext):Scope?=enterFire(context.level,context.player)
    @JvmStatic fun enterExplosion(level:Level,explosion:Explosion):Scope?=enter(level,ChangeCause.EXPLOSION,actor(explosion.indirectSourceEntity ?: explosion.directSourceEntity))
    private fun enter(level:Level,cause:ChangeCause,actor:ActorIdentity):Scope? {
        if(level !is ServerLevel || !level.server.isSameThread || !enabled(cause))return null
        return Scope(level,cause,actor).also{scopes.get().push(it)}
    }
    @JvmStatic fun exit(scope:Scope?) { if(scope!=null) { check(scopes.get().poll()===scope);if(scopes.get().isEmpty())scopes.remove() } }
    private fun fire(id:ResourceId)=id.toString() in setOf("minecraft:fire","minecraft:soul_fire")
    fun ownsFire(before:BlockStateSnapshot,after:BlockStateSnapshot)=enabled(ChangeCause.FIRE) && (fire(before.blockId)||fire(after.blockId))
    @JvmStatic fun before(level:Level,p:BlockPos,requested:BlockState):Pending? {
        if(level !is ServerLevel || !level.server.isSameThread || !enabled(ChangeCause.FIRE) && !enabled(ChangeCause.EXPLOSION))return null
        if(level.isOutsideBuildHeight(p) || !level.hasChunk(p.x shr 4,p.z shr 4))return null
        val scope=scopes.get().peek()?.takeIf{it.level===level && enabled(it.cause)}
        if(scope==null && (!enabled(ChangeCause.FIRE) || requested.block !is BaseFireBlock && level.getBlockState(p).block !is BaseFireBlock))return null
        return runCatching {
            val parent=mutations.get().peek()?.takeIf{it.level===level}
            val entries=parent?.entries ?: LinkedHashMap()
            if(p !in entries) {
                if(entries.size<4096)entries[p.immutable()]=Entry(MinecraftBlockSnapshotter.snapshot(level,p),scope?.cause ?: ChangeCause.FIRE,scope?.actor ?: ActorIdentity.Unknown,scope!=null)
                else warn(null)
            }
            Pending(level,entries,parent==null).also{mutations.get().push(it)}
        }.onFailure(::warn).getOrNull()
    }
    @JvmStatic fun after(pending:Pending?,accepted:Boolean) {
        if(pending==null)return
        check(mutations.get().poll()===pending);if(mutations.get().isEmpty())mutations.remove()
        if(!pending.root || !accepted)return
        for((p,entry) in pending.entries)runCatching {
            if(!enabled(entry.cause))return@runCatching
            val after=MinecraftBlockSnapshotter.snapshot(pending.level,p);val before=entry.before
            if(before==after)return@runCatching
            if(entry.cause==ChangeCause.FIRE) {
                if(fire(before.blockId) && before.blockId==after.blockId)return@runCatching // Fire age/shape is not another incident.
                val litChange=before.blockId==after.blockId && before.properties["lit"]!=after.properties["lit"]
                val burned=entry.scoped && before.blockId.toString() !in setOf("minecraft:air","minecraft:cave_air","minecraft:void_air") && after.blockId.toString() in setOf("minecraft:air","minecraft:cave_air","minecraft:void_air")
                if(!fire(before.blockId) && !fire(after.blockId) && !litChange && !burned)return@runCatching
            }
            val airBefore=before.blockId.toString() in setOf("minecraft:air","minecraft:cave_air","minecraft:void_air")
            val airAfter=after.blockId.toString() in setOf("minecraft:air","minecraft:cave_air","minecraft:void_air")
            val action=if(airBefore)ActionType.BLOCK_PLACE else if(airAfter)ActionType.BLOCK_BREAK else ActionType.BLOCK_CHANGE
            val row=BlockChangeSnapshot(System.currentTimeMillis(),entry.actor,ResourceId.parse(pending.level.dimension().location().toString()),BlockPosition(p.x,p.y,p.z),before,after,entry.cause,action)
            val writer=pipeline() ?: return@runCatching
            if(writer.submit(row)!=SubmissionResult.ACCEPTED)warn(null)
        }.onFailure(::warn)
    }
    private fun warn(t:Throwable?) { val now=System.currentTimeMillis();if(now-lastWarning>=10000){lastWarning=now;LoggerFactory.getLogger("Guardian/Environment").warn("Fire/explosion audit capture failed, exceeded its bounded observation or queue limit; check Guardian status",t)} }
}
