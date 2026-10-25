package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.logging.BufferedLogPipeline
import com.bareminimumstudios.guardian.logging.SubmissionResult
import com.bareminimumstudios.guardian.logging.container.ActionCaptureScope
import com.bareminimumstudios.guardian.logging.container.ContainerTransactionCorrelation
import com.bareminimumstudios.guardian.logging.container.HopperTransferCorrelation
import com.bareminimumstudios.guardian.storage.codec.ContainerChangesCodec
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.HopperBlock
import net.minecraft.world.level.block.entity.Hopper
import net.minecraft.world.level.block.entity.HopperBlockEntity
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.AABB
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong

/** Observe candidate inventories without running vanilla's randomized entity-container selection twice. */
object HopperTransferCapture {
    private val logger=LoggerFactory.getLogger("Guardian/HopperCapture")
    @Volatile private var pipeline:()->BufferedLogPipeline?={null}
    @Volatile private var enabled:()->Boolean={false}
    private val scope=ActionCaptureScope();private val scopeKey=UUID(0,0)
    private val submitted=AtomicLong();private val failures=AtomicLong();private val backpressure=AtomicLong()
    fun install(pipeline:()->BufferedLogPipeline?,enabled:()->Boolean) { this.pipeline=pipeline;this.enabled=enabled }
    fun status()="Hopper audit: submitted="+submitted.get()+" failures="+failures.get()+" backpressure="+backpressure.get()
    class Pending internal constructor(internal val level:ServerLevel,internal val source:List<Container>,internal val destination:List<Container>,internal val anchors:Map<Container,ItemSlotOwner.EntityContainer>,internal val correlation:HopperTransferCorrelation,private val lease:ActionCaptureScope.Lease):AutoCloseable { override fun close()=lease.close() }
    class CollectionPending internal constructor(internal val level:ServerLevel,internal val destination:Container,internal val anchor:ItemSlotOwner.EntityContainer?,internal val source:ItemEntity,internal val beforeItem:ItemStackSnapshot,internal val correlation:ContainerTransactionCorrelation,private val lease:ActionCaptureScope.Lease):AutoCloseable { override fun close()=lease.close() }
    private fun ready(level:Level)=enabled() && level is ServerLevel && level.server.isSameThread

    private fun candidates(level:ServerLevel,x:Double,y:Double,z:Double):List<Container> {
        val pos=BlockPos.containing(x,y,z)
        MinecraftBlockContainerSnapshotter.resolve(level,pos)?.let { return listOf(it) }
        val carts=ArrayList<AbstractMinecartContainer>()
        level.getEntities(EntityTypeTest.forClass(AbstractMinecartContainer::class.java),AABB(x-.5,y-.5,z-.5,x+.5,y+.5,z+.5),{ it.isAlive },carts,17)
        require(carts.size<=16) { "Too many hopper inventory candidates" }
        return carts
    }
    @JvmStatic fun beginPush(level:Level,hopper:HopperBlockEntity):Pending? {
        if(!ready(level))return null
        return runCatching {
            val pos=hopper.blockPos.relative(hopper.blockState.getValue(HopperBlock.FACING))
            begin(level as ServerLevel,listOf(hopper),candidates(level,pos.x+.5,pos.y+.5,pos.z+.5),ActorIdentity.System("minecraft:hopper"))
        }.onFailure(::failed).getOrNull()
    }
    @JvmStatic fun beginPull(level:Level,hopper:Hopper):Pending? {
        if(!ready(level))return null
        return runCatching {
            begin(level as ServerLevel,candidates(level,hopper.levelX,hopper.levelY+1.0,hopper.levelZ),listOf(hopper),actor(hopper))
        }.onFailure(::failed).getOrNull()
    }
    private fun actor(container:Container)=ActorIdentity.System(if(container is AbstractMinecartContainer)"minecraft:hopper_minecart" else "minecraft:hopper")
    private fun snapshot(level:ServerLevel,containers:List<Container>,anchors:MutableMap<Container,ItemSlotOwner.EntityContainer>,remember:Boolean):InventorySnapshot {
        val slots=linkedMapOf<ItemSlotAddress,ItemStackSnapshot>();var bytes=0L
        for(container in containers) {
            val value=checkNotNull(MinecartInventorySnapshotter.capture(container,level,anchors[container])) { "Hopper inventory is unavailable or sealed loot" }
            if(remember)value.slots.keys.mapNotNull { it.owner as? ItemSlotOwner.EntityContainer }.firstOrNull()?.let { anchors[container]=it }
            require(slots.keys.intersect(value.slots.keys).isEmpty()) { "Hopper inventories alias storage" }
            slots.putAll(value.slots);bytes+=value.slots.values.sumOf { (it.itemData?.size ?: 0).toLong() }
            require(slots.size<=ContainerChangesCodec.MAX_SLOTS && bytes<=16L*1024*1024) { "Hopper audit exceeds its budget" }
        }
        return InventorySnapshot(slots)
    }
    private fun begin(level:ServerLevel,source:List<Container>,destination:List<Container>,actor:ActorIdentity.System):Pending? {
        if(source.isEmpty() || destination.isEmpty())return null
        val lease=scope.enter(scopeKey) ?: return null;var retained=false
        try {
            val anchors=IdentityHashMap<Container,ItemSlotOwner.EntityContainer>()
            val a=snapshot(level,source,anchors,true);val b=snapshot(level,destination,anchors,true)
            require(a.slots.size+b.slots.size<=ContainerChangesCodec.MAX_SLOTS)
            val before=InventorySnapshot(a.slots+b.slots)
            val sources=a.slots.keys.map { it.owner }.toSet();val destinations=b.slots.keys.map { it.owner }.toSet()
            return Pending(level,source,destination,anchors,HopperTransferCorrelation(before,sources,destinations,actor),lease).also { retained=true }
        } finally { if(!retained)lease.close() }
    }
    @JvmStatic fun finish(pending:Pending?) {
        if(pending==null)return
        runCatching {
            val anchors=IdentityHashMap(pending.anchors)
            val a=snapshot(pending.level,pending.source,anchors,false);val b=snapshot(pending.level,pending.destination,anchors,false)
            val transaction=pending.correlation.finish(InventorySnapshot(a.slots+b.slots)) ?: return@runCatching
            submit(transaction)
        }.onFailure(::failed)
    }
    @JvmStatic fun beginCollection(container:Container,item:ItemEntity):CollectionPending? {
        val level=item.level() as? ServerLevel ?: return null
        if(!ready(level) || item.isRemoved || level.getEntity(item.uuid)!==item)return null
        val lease=scope.enter(scopeKey) ?: return null;var retained=false
        try {
            return runCatching {
                val before=checkNotNull(MinecartInventorySnapshotter.capture(container,level))
                val anchor=before.slots.keys.mapNotNull { it.owner as? ItemSlotOwner.EntityContainer }.firstOrNull()
                CollectionPending(level,container,anchor,item,MinecraftItemSnapshotter.capture(item.item,level.registryAccess()),ContainerTransactionCorrelation(actor(container),0,ContainerAction.HOPPER_COLLECT,before),lease).also { retained=true }
            }.onFailure(::failed).getOrNull()
        } finally { if(!retained)lease.close() }
    }
    @JvmStatic fun finishCollection(pending:CollectionPending?) {
        if(pending==null)return
        runCatching {
            val after=checkNotNull(MinecartInventorySnapshotter.capture(pending.destination,pending.level,pending.anchor))
            val transaction=pending.correlation.finish(after,true) ?: return@runCatching
            val remaining=if(pending.source.isRemoved)ItemStackSnapshot.EMPTY else MinecraftItemSnapshotter.capture(pending.source.item,pending.level.registryAccess())
            val initial=pending.beforeItem
            require(!initial.isEmpty && (remaining.isEmpty || initial.itemId==remaining.itemId && initial.itemData==remaining.itemData))
            val removed=initial.count-remaining.count;require(removed>0)
            val net=mutableMapOf<Pair<ResourceId?,BinaryPayload?>,Long>()
            transaction.changes.forEach {
                if(!it.before.isEmpty)net.merge(it.before.itemId to it.before.itemData,-it.before.count.toLong(),Long::plus)
                if(!it.after.isEmpty)net.merge(it.after.itemId to it.after.itemData,it.after.count.toLong(),Long::plus)
            }
            net.entries.removeIf { it.value==0L }
            require(net==mapOf((initial.itemId to initial.itemData) to removed.toLong())) { "Collected world item does not match inventory delta" }
            submit(transaction)
        }.onFailure(::failed)
    }
    private fun submit(transaction:ContainerTransactionSnapshot) {
        ContainerChangesCodec.encode(transaction.changes)
        when(pipeline()?.submit(ContainerAuditEntry(transaction)) ?: SubmissionResult.NOT_RUNNING) {
            SubmissionResult.ACCEPTED->submitted.incrementAndGet()
            SubmissionResult.BACKPRESSURE->{val count=backpressure.incrementAndGet();if(count==1L || count and(count-1L)==0L)logger.error("Hopper audit queue is saturated; unqueued transactions={}",count)}
            SubmissionResult.NOT_RUNNING->failed(IllegalStateException("Hopper audit writer is unavailable"))
        }
    }
    private fun failed(error:Throwable) { val count=failures.incrementAndGet();if(count==1L || count and(count-1L)==0L)logger.error("Unable to capture hopper transaction; failures={}",count,error) }
}
