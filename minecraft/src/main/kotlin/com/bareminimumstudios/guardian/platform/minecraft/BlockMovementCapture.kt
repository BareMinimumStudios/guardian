package com.bareminimumstudios.guardian.platform.minecraft
import com.bareminimumstudios.guardian.config.GuardianConfig
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.logging.BufferedLogPipeline
import com.bareminimumstudios.guardian.logging.SubmissionResult
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.piston.PistonHeadBlock
import net.minecraft.world.level.block.piston.PistonStructureResolver
import net.minecraft.world.level.block.state.properties.PistonType
import org.slf4j.LoggerFactory
import java.util.UUID

/** Bounded observation only: no chunks are loaded and no movement is forced to finish. */
object BlockMovementCapture {
    private var pipeline:()->BufferedLogPipeline?={null}
    private var config:()->GuardianConfig?={null}
    class PistonFrame internal constructor(val level:ServerLevel,val base:BlockPos,val direction:Direction,val extending:Boolean,val sticky:Boolean,val headBefore:BlockStateSnapshot) {
        internal var plan:Plan?=null
    }
    internal class Plan(val level:ServerLevel,val id:UUID,val before:Map<BlockPos,BlockStateSnapshot>,val expected:Map<BlockPos,BlockStateSnapshot>,val deadline:Long)
    class FallStart internal constructor(val level:ServerLevel,val pos:BlockPos,val before:BlockStateSnapshot,val expected:BlockStateSnapshot)
    class FallTick internal constructor(val entity:FallingBlockEntity,val level:ServerLevel) { internal var landing:Pair<BlockPos,BlockStateSnapshot>?=null }
    private val pistonFrames=ThreadLocal.withInitial{java.util.ArrayDeque<PistonFrame>()}
    private val fallFrames=ThreadLocal.withInitial{java.util.ArrayDeque<FallTick>()}
    private val pending=mutableListOf<Plan>()
    private var lastWarning=0L
    fun install(writer:()->BufferedLogPipeline?,settings:()->GuardianConfig?) {
        if(pending.isNotEmpty()) { tick();if(pending.isNotEmpty())warn(null) }
        pending.clear();pistonFrames.remove();fallFrames.remove();pipeline=writer;config=settings
    }
    private fun enabled(piston:Boolean):Boolean {
        val c=config() ?: return false
        return c.general.enabled.get() && c.logging.enabled.get() && if(piston)c.environment.pistons.get() else c.environment.fallingBlocks.get()
    }
    private fun loaded(level:ServerLevel,p:BlockPos)=!level.isOutsideBuildHeight(p)&&level.hasChunk(p.x shr 4,p.z shr 4)
    @JvmStatic fun beginPiston(level:Level,pos:BlockPos,direction:Direction,extending:Boolean,sticky:Boolean):PistonFrame? {
        if(level !is ServerLevel || !level.server.isSameThread || !enabled(true) || !loaded(level,pos.relative(direction)))return null
        return runCatching{PistonFrame(level,pos.immutable(),direction,extending,sticky,MinecraftBlockSnapshotter.snapshot(level,pos.relative(direction))).also{pistonFrames.get().push(it)}}.onFailure(::warn).getOrNull()
    }
    @JvmStatic fun resolved(resolver:PistonStructureResolver,accepted:Boolean) {
        val frame=pistonFrames.get().peek() ?: return
        if(!accepted)return
        runCatching {
            val push=resolver.toPush.map{it.immutable()};val destroy=resolver.toDestroy.map{it.immutable()}
            if(push.isEmpty()&&destroy.isEmpty())return
            val direction=resolver.pushDirection
            val positions=(push+push.map{it.relative(direction)}+destroy+frame.base.relative(frame.direction)).toSet()
            if(positions.size>64 || positions.any{!loaded(frame.level,it)}) {warn(null);return}
            val before=positions.associateWith{if(it==frame.base.relative(frame.direction))frame.headBefore else MinecraftBlockSnapshotter.snapshot(frame.level,it)}
            if(before.values.any{it.blockId.toString()=="minecraft:moving_piston"} || push.any{before.getValue(it).blockEntityData!=null}) {warn(null);return}
            val air=MinecraftBlockSnapshotter.snapshot(frame.level,Blocks.AIR.defaultBlockState(),null)
            val expected=before.toMutableMap();(push+destroy).forEach{expected[it]=air}
            push.forEach{expected[it.relative(direction)]=before.getValue(it)}
            if(frame.extending) {
                val head=Blocks.PISTON_HEAD.defaultBlockState().setValue(PistonHeadBlock.FACING,frame.direction).setValue(PistonHeadBlock.TYPE,if(frame.sticky)PistonType.STICKY else PistonType.DEFAULT)
                expected[frame.base.relative(frame.direction)]=MinecraftBlockSnapshotter.snapshot(frame.level,head,null)
            }
            frame.plan=Plan(frame.level,UUID.randomUUID(),before.toMap(),expected.toMap(),frame.level.gameTime+20)
        }.onFailure(::warn)
    }
    @JvmStatic fun endPiston(frame:PistonFrame?,accepted:Boolean) {
        if(frame==null)return
        check(pistonFrames.get().poll()===frame);if(pistonFrames.get().isEmpty())pistonFrames.remove()
        val plan=frame.plan ?: return
        if(!accepted || !enabled(true))return
        val overlaps=pending.filter{it.level===plan.level && it.before.keys.any{p->p in plan.before}}
        if(overlaps.isNotEmpty()){pending.removeAll(overlaps.toSet());warn(null);return}
        if(pending.size>=128){warn(null);return};pending.add(plan)
    }
    fun tick() {
        if(!enabled(true)){pending.clear();return}
        val iterator=pending.iterator()
        while(iterator.hasNext()) {
            val plan=iterator.next()
            var removed=false
            fun discard(){if(!removed){iterator.remove();removed=true}}
            runCatching {
                if(plan.before.keys.any{!loaded(plan.level,it)}){discard();warn(null);return@runCatching}
                if(plan.before.keys.any{plan.level.getBlockState(it).`is`(Blocks.MOVING_PISTON)}) {
                    if(plan.level.gameTime>plan.deadline){discard();warn(null)}
                    return@runCatching
                }
                val actual=plan.before.keys.associateWith{MinecraftBlockSnapshotter.snapshot(plan.level,it)}
                discard()
                if(actual!=plan.expected){warn(null);return@runCatching}
                for((pos,before)in plan.before)if(before!=actual.getValue(pos))submit(plan.level,pos,before,actual.getValue(pos),ChangeCause.PISTON,plan.id,BlockMovementPhase.PISTON)
            }.onFailure{discard();warn(it)}
        }
    }
    @JvmStatic fun beginFall(level:Level,pos:BlockPos):FallStart? {
        if(level !is ServerLevel || !level.server.isSameThread || !enabled(false) || !loaded(level,pos))return null
        return runCatching {val state=level.getBlockState(pos);FallStart(level,pos.immutable(),MinecraftBlockSnapshotter.snapshot(level,pos),MinecraftBlockSnapshotter.snapshot(level,state.fluidState.createLegacyBlock(),null))}.onFailure(::warn).getOrNull()
    }
    @JvmStatic fun endFall(start:FallStart?,entity:FallingBlockEntity) {
        if(start==null || !enabled(false))return
        runCatching {
            if(start.level.getEntity(entity.uuid)!==entity || entity.isRemoved){warn(null);return}
            val after=MinecraftBlockSnapshotter.snapshot(start.level,start.pos)
            if(after!=start.expected || after==start.before)return
            if(start.before.blockId!=MinecraftBlockSnapshotter.snapshot(start.level,entity.blockState,null).blockId){warn(null);return}
            submit(start.level,start.pos,start.before,after,ChangeCause.GRAVITY,entity.uuid,BlockMovementPhase.FALL_START)
        }.onFailure(::warn)
    }
    @JvmStatic fun beginFallTick(entity:FallingBlockEntity):FallTick? {
        val level=entity.level()
        if(level !is ServerLevel || !level.server.isSameThread || !enabled(false) || entity.isRemoved || level.getEntity(entity.uuid)!==entity)return null
        return FallTick(entity,level).also{fallFrames.get().push(it)}
    }
    @JvmStatic fun beforeLanding(level:Level,pos:BlockPos):BlockStateSnapshot? {
        val frame=fallFrames.get().peek() ?: return null
        if(frame.level!==level || !loaded(frame.level,pos))return null
        return runCatching{MinecraftBlockSnapshotter.snapshot(level,pos)}.onFailure(::warn).getOrNull()
    }
    @JvmStatic fun landed(pos:BlockPos,before:BlockStateSnapshot?,accepted:Boolean) {
        val frame=fallFrames.get().peek() ?: return
        if(accepted&&before!=null)frame.landing=pos.immutable() to before
    }
    @JvmStatic fun endFallTick(frame:FallTick?) {
        if(frame==null)return
        check(fallFrames.get().poll()===frame);if(fallFrames.get().isEmpty())fallFrames.remove()
        val (pos,before)=frame.landing ?: return
        if(!enabled(false))return
        runCatching {
            val after=MinecraftBlockSnapshotter.snapshot(frame.level,pos)
            val placed=MinecraftBlockSnapshotter.snapshot(frame.level,frame.entity.blockState,null)
            if(after.blockId!=placed.blockId || after==before){warn(null);return}
            submit(frame.level,pos,before,after,ChangeCause.GRAVITY,frame.entity.uuid,BlockMovementPhase.FALL_LAND)
        }.onFailure(::warn)
    }
    private fun submit(level:ServerLevel,pos:BlockPos,before:BlockStateSnapshot,after:BlockStateSnapshot,cause:ChangeCause,id:UUID,phase:BlockMovementPhase) {
        val air=setOf("minecraft:air","minecraft:cave_air","minecraft:void_air")
        val action=if(before.blockId.toString() in air)ActionType.BLOCK_PLACE else if(after.blockId.toString() in air)ActionType.BLOCK_BREAK else ActionType.BLOCK_CHANGE
        val row=BlockChangeSnapshot(System.currentTimeMillis(),ActorIdentity.Unknown,ResourceId.parse(level.dimension().location().toString()),BlockPosition(pos.x,pos.y,pos.z),before,after,cause,action,movementId=id,movementPhase=phase)
        val writer=pipeline() ?: return
        if(writer.submit(row)!=SubmissionResult.ACCEPTED)warn(null)
    }
    private fun warn(t:Throwable?) {val now=System.currentTimeMillis();if(now-lastWarning>=10000){lastWarning=now;LoggerFactory.getLogger("Guardian/Movement").warn("Movement history could not verify a bounded observation or queue submission; history may be incomplete",t)}}
}
