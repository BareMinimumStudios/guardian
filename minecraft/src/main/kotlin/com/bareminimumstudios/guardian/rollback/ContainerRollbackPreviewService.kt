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
class ContainerRollbackPreviewService(private val server: MinecraftServer, private val history: BlockHistoryService) {
    private val logger = LoggerFactory.getLogger("Guardian/ItemRollbackPreview")
    private var active: Session? = null

    fun request(source: CommandSourceStack, query: ContainerLookupQuery, maxRecords: Int = 50): Boolean {
        require(maxRecords in 1..50)
        if (active != null) { source.sendFailure(Component.literal("Guardian already has an item rollback preview in progress.")); return false }
        val session = Session(source,query)
        active = session
        source.sendSystemMessage(Component.literal("Guardian: checking item rollback candidates; no items will be changed..."))
        history.lookupContainers(query.copy(limit=maxRecords+1,offset=0,oldestFirst=false), { rows ->
            if (active !== session) return@lookupContainers
            if (rows.size > maxRecords) { refuse(session,"More than $maxRecords transactions match. Narrow t:, r:, or u:."); return@lookupContainers }
            val owners=rows.flatMap { it.changes.map { change -> change.address.owner } }.distinct()
            if (owners.size > 32 || rows.sumOf { it.changes.size } > 2048) { refuse(session,"Too many inventories or changed slots match. Narrow the filters."); return@lookupContainers }
            session.rows=rows
            session.wanted=rows.flatMap { it.changes }.map { it.address }.toSet()
            session.owners.addAll(owners.filter { it is ItemSlotOwner.BlockContainer || it is ItemSlotOwner.PlayerInventory })
            session.ready=true
        }, { if (active === session) refuse(session,"History lookup failed; see the server log.") })
        return true
    }

    fun tick() {
        val session=active ?: return
        if (!session.ready) return
        val owner=session.owners.pollFirst()
        if (owner != null) {
            if (owner is ItemSlotOwner.BlockContainer && !inside(owner,session.query)) session.outside.add(owner)
            else try {
                val snapshot=read(owner)
                if (snapshot == null) session.unavailable.add(owner)
                else {
                    // Keep only addresses referenced by the selected history, bounded to 2048 slots.
                    snapshot.slots.filterKeys { it in session.wanted }.forEach { (address,item) -> session.live[address]=item }
                }
            } catch (error: Throwable) {
                logger.warn("Could not observe inventory for item rollback preview",error)
                session.unavailable.add(owner)
            }
            return
        }
        val preview=ContainerRollbackPlanner.plan(session.rows,InventorySnapshot(session.live),session.unavailable,session.outside)
        val summary=preview.entries.groupingBy { it.reason }.eachCount()
        session.source.sendSystemMessage(Component.literal("Guardian item rollback preview: ${preview.eligible} eligible, ${preview.entries.size-preview.eligible} skipped (${preview.entries.size} transactions checked)."))
        for ((reason,count) in summary) if (reason != ContainerPreviewReason.ELIGIBLE) session.source.sendSystemMessage(Component.literal("  $count: ${label(reason)}"))
        session.source.sendSystemMessage(Component.literal("Preview only: no items changed. Eligible means the observed slots match; item rollback apply is not enabled yet."))
        active=null
    }

    fun stop() { active=null }

    private fun read(owner: ItemSlotOwner): InventorySnapshot? = when (owner) {
        is ItemSlotOwner.BlockContainer -> {
            val world=server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(owner.dimension.toString())))
            if (world == null) null else {
                val container=MinecraftBlockContainerSnapshotter.resolve(world,net.minecraft.core.BlockPos(owner.position.x,owner.position.y,owner.position.z))
                container?.let { MinecraftBlockContainerSnapshotter.capture(it,world) }
            }
        }
        is ItemSlotOwner.PlayerInventory -> {
            val player=server.playerList.getPlayer(owner.playerId)
            if (player == null || player.containerMenu !== player.inventoryMenu || !player.inventoryMenu.carried.isEmpty || (1..4).any { !player.inventoryMenu.slots[it].item.isEmpty }) null
            else MinecraftInventorySnapshotter.capturePlayer(player.inventoryMenu,player.inventory,player.uuid,player.registryAccess())
        }
        else -> null
    }

    private fun inside(owner: ItemSlotOwner.BlockContainer,query: ContainerLookupQuery): Boolean {
        if (owner.dimension != query.dimension) return false
        val bounds=query.bounds
        if (bounds != null) return bounds.contains(owner.position)
        val center=query.position ?: return false
        val radius=query.radius ?: 0
        return kotlin.math.abs(owner.position.x.toLong()-center.x) <= radius && kotlin.math.abs(owner.position.y.toLong()-center.y) <= radius && kotlin.math.abs(owner.position.z.toLong()-center.z) <= radius
    }
    private fun refuse(session: Session, message: String) { session.source.sendFailure(Component.literal("Guardian item rollback preview: $message No items changed."));active=null }
    private fun label(reason: ContainerPreviewReason) = when(reason) {
        ContainerPreviewReason.UNSUPPORTED_ACTION -> "crafting, creative, drop, close or unsupported action"
        ContainerPreviewReason.AMBIGUOUS_ORDER -> "shared inventory records have the same timestamp; order is uncertain"
        ContainerPreviewReason.UNSUPPORTED_OWNER -> "temporary cursor/crafting or unsupported ownership"
        ContainerPreviewReason.NONCONSERVING_ACTION -> "creation, destruction, drop or recipe transformation"
        ContainerPreviewReason.UNAVAILABLE_OWNER -> "offline/busy player, missing/unloaded container, sealed loot or unreadable items"
        ContainerPreviewReason.OUTSIDE_SCOPE -> "a transfer endpoint is outside the requested region"
        ContainerPreviewReason.STATE_MISMATCH -> "recorded slots do not match the observed inventory"
        ContainerPreviewReason.BLOCKED_CHAIN -> "an older transaction depends on a skipped inventory"
        ContainerPreviewReason.ELIGIBLE -> "eligible"
    }
    private class Session(val source: CommandSourceStack,val query: ContainerLookupQuery) {
        var rows: List<ContainerTransactionSnapshot> = emptyList()
        val owners=ArrayDeque<ItemSlotOwner>()
        val live=linkedMapOf<ItemSlotAddress,ItemStackSnapshot>()
        val unavailable=mutableSetOf<ItemSlotOwner>()
        val outside=mutableSetOf<ItemSlotOwner>()
        var wanted: Set<ItemSlotAddress> = emptySet()
        var ready=false
    }
}
