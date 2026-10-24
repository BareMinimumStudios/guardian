package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.config.GuardianConfig
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.logging.BufferedLogPipeline
import com.bareminimumstudios.guardian.logging.SubmissionResult
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import org.slf4j.LoggerFactory
import java.util.WeakHashMap

/** Server-thread-only, bounded provenance. It deliberately does not survive restart or expiry. */
object FluidFlowCapture {
    private var pipeline: () -> BufferedLogPipeline? = { null }
    private var config: () -> GuardianConfig? = { null }
    private val worlds=WeakHashMap<ServerLevel,LinkedHashMap<BlockPos,Trace>>()
    private data class Origin(val position:BlockPos,val actor:ActorIdentity.Player,val expires:Long)
    private data class Trace(val origin:Origin,val snapshot:FluidSnapshot)
    @ConsistentCopyVisibility
    data class Scope internal constructor(val level:ServerLevel,val origin:BlockPos,val actor:ActorIdentity,val formation:Boolean)
    @ConsistentCopyVisibility
    data class Mutation internal constructor(val level:ServerLevel,val position:BlockPos,val before:FluidSnapshot,val scope:Scope?)
    private val scopes=ThreadLocal.withInitial { java.util.ArrayDeque<Scope>() }
    private var lastWarning=0L
    fun install(writer:()->BufferedLogPipeline?,settings:()->GuardianConfig?) { pipeline=writer;config=settings;worlds.clear();scopes.remove() }
    private fun enabled():Boolean {
        val c=config() ?: return false
        return c.general.enabled.get() && c.logging.enabled.get() && c.fluids.naturalFlow.get()
    }
    private fun loaded(level:ServerLevel,p:BlockPos)=!level.isOutsideBuildHeight(p) && level.hasChunk(p.x shr 4,p.z shr 4)
    private fun trace(level:ServerLevel,p:BlockPos):Trace? {
        val map=worlds[level] ?: return null;val value=map[p] ?: return null;val root=map[value.origin.position]
        if(value.origin.expires<=level.gameTime || root?.origin !== value.origin || !loaded(level,p) || !loaded(level,value.origin.position) || FluidHistoryCapture.snapshot(level,p)!=value.snapshot || FluidHistoryCapture.snapshot(level,value.origin.position)!=root.snapshot) { map.remove(p);return null }
        return value
    }
    private fun put(level:ServerLevel,p:BlockPos,value:Trace) {
        val map=worlds.getOrPut(level){LinkedHashMap()};map[p.immutable()]=value
        while(map.size>8192)map.remove(map.keys.first())
    }
    fun bucket(level:ServerLevel,p:BlockPos,actor:ActorIdentity.Player,after:FluidSnapshot) {
        if(!enabled()) { worlds.clear();return }
        worlds[level]?.remove(p)
        if(!after.empty && after.source)put(level,p,Trace(Origin(p.immutable(),actor,level.gameTime+1200),after))
    }
    @JvmStatic fun unload(level:ServerLevel,x:Int,z:Int) { worlds[level]?.entries?.removeIf { (p,t)->p.x shr 4==x && p.z shr 4==z || t.origin.position.x shr 4==x && t.origin.position.z shr 4==z } }
    @JvmStatic fun enter(level:Level,p:BlockPos,formation:Boolean):Scope? {
        if(level !is ServerLevel || !level.server.isSameThread || !enabled() || !loaded(level,p))return null
        return runCatching {
            val own=trace(level,p);var actor:ActorIdentity=own?.origin?.actor ?: ActorIdentity.Unknown
            // A competing or untracked feeding neighbour makes attribution ambiguous.
            val fluid=level.getFluidState(p)
            if(own!=null)for(d in listOf(Direction.UP,Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST)) {
                val neighbour=p.relative(d)
                if(!loaded(level,neighbour)) { actor=ActorIdentity.Unknown;break }
                if((if(formation) !level.getFluidState(neighbour).isEmpty else level.getFluidState(neighbour).type.isSame(fluid.type)) && trace(level,neighbour)?.origin?.actor!=own.origin.actor) { actor=ActorIdentity.Unknown;break }
            }
            Scope(level,p.immutable(),actor,formation).also { scopes.get().push(it) }
        }.onFailure(::warn).getOrNull()
    }
    @JvmStatic fun exit(scope:Scope?) { if(scope!=null) { val stack=scopes.get();check(stack.poll()===scope);if(stack.isEmpty())scopes.remove() } }
    @JvmStatic fun before(level:Level,p:BlockPos):Mutation? {
        if(level !is ServerLevel || !level.server.isSameThread)return null
        if(!enabled()) { worlds.clear();return null }
        if(!loaded(level,p))return null
        val scope=scopes.get().peek()?.takeIf { it.level===level }
        if(scope==null && worlds[level]?.containsKey(p)!=true)return null
        return runCatching { Mutation(level,p.immutable(),FluidHistoryCapture.snapshot(level,p),scope) }.onFailure(::warn).getOrNull()
    }
    @JvmStatic fun after(mutation:Mutation?,accepted:Boolean) {
        if(mutation==null || !accepted)return
        runCatching {
            val (level,p,before,scope)=mutation;val after=FluidHistoryCapture.snapshot(level,p)
            if(before==after)return
            val inherited=scope?.let { trace(level,it.origin) }
            worlds[level]?.remove(p)
            if(scope==null || !enabled() || before.empty && after.empty)return
            if(!after.empty && scope.actor is ActorIdentity.Player && inherited?.origin?.actor==scope.actor)put(level,p,Trace(inherited.origin,after))
            val formed=!before.empty && after.empty && before.blockId != after.blockId && (scope.formation || after.blockId.toString() in setOf("minecraft:stone","minecraft:cobblestone","minecraft:obsidian","minecraft:basalt"))
            val row=FluidAuditEvent(timestampEpochMillis=System.currentTimeMillis(),actor=scope.actor,
                dimension=ResourceId.parse(level.dimension().location().toString()),position=BlockPosition(p.x,p.y,p.z),
                kind=if(formed)FluidAuditKind.FORM else FluidAuditKind.FLOW,before=before,after=after)
            val writer=pipeline() ?: return
            if(writer.submit(row)!=SubmissionResult.ACCEPTED)warn(null)
        }.onFailure(::warn)
    }
    private fun warn(t:Throwable?) { val now=System.currentTimeMillis();if(now-lastWarning>=10000) { lastWarning=now;LoggerFactory.getLogger("Guardian/Fluids").warn("Natural fluid history failed or its bounded queue rejected a record; check Guardian status",t) } }
}
