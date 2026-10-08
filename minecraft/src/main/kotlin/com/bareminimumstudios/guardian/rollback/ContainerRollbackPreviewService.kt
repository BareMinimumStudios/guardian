package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.lookup.BlockHistoryService
import com.bareminimumstudios.guardian.platform.minecraft.MinecraftBlockContainerSnapshotter
import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventorySnapshotter
import com.bareminimumstudios.guardian.storage.query.ContainerLookupQuery
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import java.util.ArrayDeque

/** One inventory read per tick, bounded preview only. No slot setters or journal writes. */
class ContainerRollbackPreviewService(private val server: MinecraftServer, private val history: BlockHistoryService, private val pipeline: com.bareminimumstudios.guardian.logging.BufferedLogPipeline,private val otherBusy: () -> Boolean = { false }) {
    private val logger = LoggerFactory.getLogger("Guardian/ItemRollbackPreview")
    private var active: Session? = null

    fun isBusy()=active!=null

    fun request(source: CommandSourceStack, query: ContainerLookupQuery, maxRecords: Int = 50): Boolean {
        require(maxRecords in 1..50)
        if (active != null || otherBusy()) { source.sendFailure(Component.literal("Guardian already has an item rollback preview in progress.")); return false }
        val session = Session(source,query)
        active = session
        source.sendSystemMessage(Component.literal("Guardian: checking item rollback candidates; no items will be changed..."))
        session.barrier=pipeline.writeBarrier()
        session.barrier!!.result.whenComplete { _, error -> server.execute {
            if(active !== session) return@execute
            if(error != null) refuse(session,"Accepted audit writes could not be confirmed; see storage status.")
            else lookup(session,maxRecords)
        } }
        return true
    }

    private fun lookup(session: Session,maxRecords: Int) {
        val query=session.query
        history.lookupContainers(query.copy(limit=maxRecords+1,offset=0,oldestFirst=false), { rows ->
            if (active !== session) return@lookupContainers
            if (rows.size > maxRecords) { refuse(session,"More than $maxRecords transactions match. Narrow t:, r:, or u:."); return@lookupContainers }
            val owners=rows.flatMap { it.changes.map { change -> change.address.owner } }.distinct()
            if (owners.size > 32 || rows.sumOf { it.changes.size } > 2048) { refuse(session,"Too many inventories or changed slots match. Narrow the filters."); return@lookupContainers }
            session.rows=rows
            session.wanted=rows.flatMap { it.changes }.map { it.address }.toSet()
            session.owners.addAll(owners.filter { it is ItemSlotOwner.BlockContainer || it is ItemSlotOwner.PlayerInventory })
            history.guardContainers(rows,{ guard ->
                if(active !== session) return@guardContainers
                if(guard == null) { refuse(session,"This backend cannot verify persistent item rollback history.");return@guardContainers }
                session.guard=guard
                val watched=owners.filter { it is ItemSlotOwner.BlockContainer || it is ItemSlotOwner.PlayerInventory }
                if(watched.isNotEmpty()) session.watch=runCatching { pipeline.observeOwners(watched) }.getOrElse { refuse(session,"Owner observation is unavailable.");return@guardContainers }
                session.ready=true
            }, { if(active === session) refuse(session,"History safety check failed; see the server log.") })
        }, { if (active === session) refuse(session,"History lookup failed; see the server log.") })
    }

    fun tick() {
        val session=active ?: return
        if (!session.ready) {
            if(System.nanoTime()-session.startedAt > java.util.concurrent.TimeUnit.SECONDS.toNanos(10)) refuse(session,"Timed out waiting for audit writes or history checks.")
            return
        }
        val owner=session.owners.pollFirst()
        if (owner != null) {
            if (owner is ItemSlotOwner.BlockContainer && !inside(owner,session.query)) session.outside.add(owner)
            else try {
                val observation=MinecraftInventoryObservation.read(server,owner,session.wanted.filter { it.owner==owner }.toSet())
                val snapshot=observation?.snapshot
                if (snapshot == null) session.unavailable.add(owner)
                else {
                    val bytes=snapshot.slots.values.sumOf { (it.itemData?.size ?: 0).toLong() }
                    if(session.retainedBytes+bytes>16L*1024*1024) { session.unavailable.add(owner);return }
                    session.retainedBytes+=bytes
                    session.bindings[owner]=checkNotNull(observation)
                    // Keep only addresses referenced by the selected history, bounded to 2048 slots.
                    snapshot.slots.filterKeys { it in session.wanted }.forEach { (address,item) -> session.live[address]=item }
                }
            } catch (error: Throwable) {
                logger.warn("Could not observe inventory for item rollback preview",error)
                session.unavailable.add(owner)
            }
            return
        }
        if(!session.finalStarted) {
            session.finalStarted=true;session.ready=false
            session.barrier=pipeline.writeBarrier()
            session.barrier!!.result.whenComplete { _,error -> server.execute {
                if(active !== session) return@execute
                if(error!=null) { refuse(session,"Final audit writes could not be confirmed.");return@execute }
                history.guardContainers(session.rows,{ guard ->
                    if(active !== session) return@guardContainers
                    if(guard==null) { refuse(session,"Final history safety check is unavailable.");return@guardContainers }
                    session.guard=guard
                    finish(session)
                }, { refuse(session,"Final history safety check failed.") })
            } }
        }
    }

    private fun finish(session: Session) {
        session.watch?.changedOwners()?.let { session.unavailable.addAll(it) }
        session.bindings.filterValues { !it.isCurrent() }.keys.forEach { session.unavailable.add(it) }
        val preview=ContainerRollbackPlanner.plan(session.rows,InventorySnapshot(session.live),session.unavailable,session.outside,checkNotNull(session.guard))
        val summary=preview.entries.groupingBy { it.reason }.eachCount()
        session.source.sendSystemMessage(Component.literal("Guardian item rollback preview: ${preview.eligible} eligible, ${preview.entries.size-preview.eligible} skipped (${preview.entries.size} transactions checked)."))
        for ((reason,count) in summary) if (reason != ContainerPreviewReason.ELIGIBLE) session.source.sendSystemMessage(Component.literal("  $count: ${label(reason)}"))
        session.source.sendSystemMessage(Component.literal("Preview only: no items changed. Eligible means the observed slots match; item rollback apply is not enabled yet."))
        session.watch?.close();active=null
    }

    fun stop() { val session=active;active=null;session?.barrier?.cancel();session?.watch?.close() }

    private fun inside(owner: ItemSlotOwner.BlockContainer,query: ContainerLookupQuery): Boolean {
        if (owner.dimension != query.dimension) return false
        val bounds=query.bounds
        if (bounds != null) return bounds.contains(owner.position)
        val center=query.position ?: return false
        val radius=query.radius ?: 0
        return kotlin.math.abs(owner.position.x.toLong()-center.x) <= radius && kotlin.math.abs(owner.position.y.toLong()-center.y) <= radius && kotlin.math.abs(owner.position.z.toLong()-center.z) <= radius
    }
    private fun refuse(session: Session, message: String) { if(active !== session) return;active=null;session.barrier?.cancel();session.watch?.close();session.source.sendFailure(Component.literal("Guardian item rollback preview: $message No items changed.")) }
    private fun label(reason: ContainerPreviewReason) = when(reason) {
        ContainerPreviewReason.UNSUPPORTED_ACTION -> "crafting, creative, drop, close or unsupported action"
        ContainerPreviewReason.AMBIGUOUS_ORDER -> "shared inventory records have the same timestamp; order is uncertain"
        ContainerPreviewReason.UNSUPPORTED_OWNER -> "temporary cursor/crafting or unsupported ownership"
        ContainerPreviewReason.NONCONSERVING_ACTION -> "creation, destruction, drop or recipe transformation"
        ContainerPreviewReason.UNAVAILABLE_OWNER -> "offline/busy player, missing/unloaded container, sealed loot, changed ownership, captured activity during checking or unreadable items"
        ContainerPreviewReason.OUTSIDE_SCOPE -> "a transfer endpoint is outside the requested region"
        ContainerPreviewReason.STATE_MISMATCH -> "recorded slots do not match the observed inventory"
        ContainerPreviewReason.BLOCKED_CHAIN -> "an older transaction depends on a skipped inventory"
        ContainerPreviewReason.NEWER_HISTORY -> "newer or equally timed item history is excluded by the selected filters"
        ContainerPreviewReason.CHANGED_BLOCK -> "the container position has newer or equally timed block history"
        ContainerPreviewReason.RESERVED_OWNER -> "an inventory is reserved by an unfinished item rollback journal"
        ContainerPreviewReason.CLAIMED_SOURCE -> "the transaction is already claimed by an item rollback journal"
        ContainerPreviewReason.ELIGIBLE -> "eligible"
    }
    private class Session(val source: CommandSourceStack,val query: ContainerLookupQuery) {
        val startedAt=System.nanoTime()
        var barrier: com.bareminimumstudios.guardian.logging.AuditWriteBarrier? = null
        var rows: List<ContainerTransactionSnapshot> = emptyList()
        val owners=ArrayDeque<ItemSlotOwner>()
        val live=linkedMapOf<ItemSlotAddress,ItemStackSnapshot>()
        val unavailable=mutableSetOf<ItemSlotOwner>()
        val outside=mutableSetOf<ItemSlotOwner>()
        var wanted: Set<ItemSlotAddress> = emptySet()
        var guard: ContainerHistoryGuard? = null
        var retainedBytes=0L
        val bindings=linkedMapOf<ItemSlotOwner,MinecraftInventoryObservation>()
        var watch: com.bareminimumstudios.guardian.logging.AuditOwnerObservation? = null
        var finalStarted=false
        var ready=false
    }
}
