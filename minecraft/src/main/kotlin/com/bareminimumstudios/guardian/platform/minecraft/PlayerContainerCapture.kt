package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.logging.BufferedLogPipeline
import com.bareminimumstudios.guardian.logging.SubmissionResult
import com.bareminimumstudios.guardian.logging.container.ActionCaptureScope
import com.bareminimumstudios.guardian.logging.container.ContainerTransactionCorrelation
import com.bareminimumstudios.guardian.mixin.CompoundContainerAccessor
import com.bareminimumstudios.guardian.storage.codec.ContainerChangesCodec
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.CompoundContainer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.level.block.entity.BlockEntity
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Server-thread snapshots bracket authoritative menu and standalone player item operations. */
object PlayerContainerCapture {
    private val logger = LoggerFactory.getLogger("Guardian/ContainerCapture")
    @Volatile private var pipeline: () -> BufferedLogPipeline? = { null }
    @Volatile private var enabled: () -> Boolean = { false }
    private val scope = ActionCaptureScope()
    private val submitted = AtomicLong(); private val failures = AtomicLong(); private val backpressure = AtomicLong()
    fun install(pipeline: () -> BufferedLogPipeline?, enabled: () -> Boolean) { this.pipeline = pipeline; this.enabled = enabled }
    fun status() = "Item/container audit: submitted=${submitted.get()} failures=${failures.get()} backpressure=${backpressure.get()}"

    private val active = mutableMapOf<UUID, Pending>()

    class Pending internal constructor(
        internal val playerId: UUID,
        internal val menu: AbstractContainerMenu,
        internal val includeContainers: Boolean,
        internal val includeCrafting: Boolean,
        internal val correlation: ContainerTransactionCorrelation,
        private val lease: ActionCaptureScope.Lease
    ) : AutoCloseable {
        internal var crafted = false
        override fun close() { active.remove(playerId, this); lease.close() }
    }

    @JvmStatic fun begin(menu: AbstractContainerMenu, slot: Int, type: ClickType, player: Player): Pending? {
        if (player !is ServerPlayer) return null
        if (MinecraftCraftingSnapshotter.grid(menu, player.inventory) != null) {
            // Thrown recipe outputs need entity ownership; keep that separate future slice.
            if (!MinecraftCraftingSnapshotter.acceptsClick(menu, player.inventory, slot, type)) return null
            return begin(menu, ContainerAction.valueOf(type.name), player, false, true)
        }
        if (menu === player.inventoryMenu) return null
        return begin(menu, ContainerAction.valueOf(type.name), player, true)
    }

    @JvmStatic fun beginClose(player: ServerPlayer): Pending? {
        val crafting = MinecraftCraftingSnapshotter.grid(player.containerMenu, player.inventory) != null
        val inventoryMenu = player.containerMenu === player.inventoryMenu
        return begin(player.containerMenu, ContainerAction.CLOSE, player, !inventoryMenu && !crafting, crafting || inventoryMenu)
    }

    @JvmStatic fun beginRecipe(menu: AbstractContainerMenu, player: ServerPlayer): Pending? {
        if (MinecraftCraftingSnapshotter.grid(menu, player.inventory) == null) return null
        return begin(menu, ContainerAction.RECIPE_PLACE, player, false, true)
    }

    @JvmStatic fun markCrafted(player: Player) {
        if (player is ServerPlayer && player.server.isSameThread) active[player.uuid]?.let {
            if (it.includeCrafting) it.crafted = true
        }
    }

    @JvmStatic fun beginCreative(slot: net.minecraft.world.inventory.Slot, player: ServerPlayer): Pending? {
        if (!MinecraftInventorySnapshotter.isPlayerSlot(slot, player.inventory)) return null
        return beginPlayerAction(player, ContainerAction.CREATIVE_SET)
    }

    @JvmStatic fun beginPlayerAction(player: ServerPlayer, action: ContainerAction): Pending? = begin(player.containerMenu, action, player, false)

    private fun begin(menu: AbstractContainerMenu, action: ContainerAction, player: Player, includeContainers: Boolean, includeCrafting: Boolean = false): Pending? {
        if (!enabled() || player !is ServerPlayer || player.containerMenu !== menu || !player.server.isSameThread) return null
        val lease = scope.enter(player.uuid) ?: return null
        var retained = false
        try {
            return runCatching {
                val before = snapshot(menu, player, includeContainers, includeCrafting) ?: return@runCatching null
                Pending(player.uuid, menu, includeContainers, includeCrafting, ContainerTransactionCorrelation(MinecraftActorAdapter.player(player), menu.containerId, action, before, contexts = if (includeCrafting) MinecraftCraftingSnapshotter.context(menu, player) else emptyList()), lease).also { retained = true; active[player.uuid] = it }
            }.onFailure(::failed).getOrNull()
        } finally { if (!retained) lease.close() }
    }

    @JvmStatic fun finish(pending: Pending?, player: Player) {
        if (pending == null || player !is ServerPlayer || pending.playerId != player.uuid) return
        runCatching {
            // Read the original menu even after doCloseContainer switches back to inventoryMenu.
            val after = snapshot(pending.menu, player, pending.includeContainers, pending.includeCrafting) ?: return@runCatching
            val transaction = pending.correlation.finish(after, true, pending.crafted) ?: return@runCatching
            ContainerChangesCodec.encode(transaction.changes)
            when (pipeline()?.submit(ContainerAuditEntry(transaction)) ?: SubmissionResult.NOT_RUNNING) {
                SubmissionResult.ACCEPTED -> submitted.incrementAndGet()
                SubmissionResult.BACKPRESSURE -> {
                    val count = backpressure.incrementAndGet()
                    if (count == 1L || count and (count - 1L) == 0L) logger.error("Item audit queue is saturated; unqueued transactions={}", count)
                }
                SubmissionResult.NOT_RUNNING -> failed(IllegalStateException("Item audit writer is unavailable"))
            }
        }.onFailure(::failed)
    }

    class DirectPending internal constructor(
        internal val block:BlockEntity,internal val playerId:UUID,internal val menu:AbstractContainerMenu,
        internal val before:InventorySnapshot,internal val correlation:ContainerTransactionCorrelation,
        private val lease:ActionCaptureScope.Lease
    ):AutoCloseable { override fun close()=lease.close() }

    @JvmStatic fun beginBlockUse(world:net.minecraft.world.level.Level,player:ServerPlayer,pos:net.minecraft.core.BlockPos):DirectPending? {
        if(!enabled() || world!==player.level() || !player.server.isSameThread || !world.hasChunk(pos.x shr 4,pos.z shr 4))return null
        val block=world.getBlockEntity(pos) ?: return null
        if(!DirectBlockItems.supported(block) || block is Container && MinecraftBlockContainerSnapshotter.hasPendingLoot(block))return null
        val lease=scope.enter(player.uuid) ?: return null
        var retained=false
        try {
            return runCatching {
                val before=directSnapshot(block,player,player.containerMenu)
                DirectPending(block,player.uuid,player.containerMenu,before,ContainerTransactionCorrelation(MinecraftActorAdapter.player(player),player.containerMenu.containerId,ContainerAction.BLOCK_USE,before),lease).also { retained=true }
            }.onFailure(::failed).getOrNull()
        } finally { if(!retained)lease.close() }
    }
    @JvmStatic fun finishBlockUse(player:ServerPlayer,pending:DirectPending?,accepted:Boolean) {
        if(pending==null)return
        try {
            if(!accepted || !enabled() || pending.playerId!=player.uuid || !player.server.isSameThread || player.containerMenu!==pending.menu)return
            val block=pending.block;val world=player.serverLevel();val pos=block.blockPos
            if(block.level!==world || !world.hasChunk(pos.x shr 4,pos.z shr 4) || world.getBlockEntity(pos)!==block)return
            runCatching {
                val after=directSnapshot(block,player,pending.menu)
                // Ignore inventory-only changes (e.g. consuming a dye) and rejected/no-op interactions.
                if(pending.before.slots.none { (address,item)->address.owner is ItemSlotOwner.BlockContainer && after.slots[address]!=item })return@runCatching
                val transaction=pending.correlation.finish(after,true) ?: return@runCatching
                ContainerChangesCodec.encode(transaction.changes)
                when(pipeline()?.submit(ContainerAuditEntry(transaction)) ?: SubmissionResult.NOT_RUNNING) {
                    SubmissionResult.ACCEPTED->submitted.incrementAndGet()
                    SubmissionResult.BACKPRESSURE->failed(IllegalStateException("Direct item audit queue is saturated"))
                    SubmissionResult.NOT_RUNNING->failed(IllegalStateException("Direct item audit writer is unavailable"))
                }
            }.onFailure(::failed)
        } finally { pending.close() }
    }
    private fun directSnapshot(block:BlockEntity,player:ServerPlayer,menu:AbstractContainerMenu):InventorySnapshot {
        val slots=LinkedHashMap(MinecraftInventorySnapshotter.capturePlayer(menu,player.inventory,player.uuid,player.registryAccess()).slots)
        val pos=block.blockPos
        val owner=ItemSlotOwner.BlockContainer(ResourceId.parse(player.serverLevel().dimension().location().toString()),BlockPosition(pos.x,pos.y,pos.z))
        DirectBlockItems.items(block).forEachIndexed { index,item->slots[ItemSlotAddress(owner,index)]=MinecraftItemSnapshotter.capture(item,player.registryAccess()) }
        require(slots.size<=ContainerChangesCodec.MAX_SLOTS)
        return InventorySnapshot(slots)
    }

    private fun failed(error: Throwable) {
        val count = failures.incrementAndGet()
        if (count == 1L || count and (count - 1L) == 0L) logger.error("Unable to capture item transaction; failures={}", count, error)
    }

    private fun snapshot(menu: AbstractContainerMenu, player: ServerPlayer, includeContainers: Boolean, includeCrafting: Boolean): InventorySnapshot? {
        if (includeContainers && menu.slots.size >= ContainerChangesCodec.MAX_SLOTS) return null
        if (includeCrafting) return MinecraftCraftingSnapshotter.capture(menu, player)
        val slots = LinkedHashMap(MinecraftInventorySnapshotter.capturePlayer(menu, player.inventory, player.uuid, player.registryAccess()).slots)
        if (includeContainers) {
            var hasBlockContainer = false
            for (slot in menu.slots) {
                if(MinecraftBlockContainerSnapshotter.hasPendingLoot(slot.container))return null
                val address = address(slot.container, slot.containerSlot, player) ?: return null
                if (address.owner is ItemSlotOwner.BlockContainer || address.owner is ItemSlotOwner.EntityContainer) hasBlockContainer = true
                if (address.owner is ItemSlotOwner.PlayerInventory) continue
                require(address !in slots) { "Menu aliases a logical inventory slot" }
                slots[address] = MinecraftItemSnapshotter.capture(slot.item, player.registryAccess())
            }
            if (!hasBlockContainer) return null
        }
        require(slots.size <= ContainerChangesCodec.MAX_SLOTS) { "Inventory capture exceeds the slot budget" }
        return InventorySnapshot(slots)
    }

    private fun address(container: Container, index: Int, player: ServerPlayer): ItemSlotAddress? = when {
        container === player.inventory -> ItemSlotAddress(ItemSlotOwner.PlayerInventory(player.uuid), index)
        container is CompoundContainer -> {
            val parts = container as CompoundContainerAccessor
            val first = parts.`guardian$first`()
            if (index < first.containerSize) address(first, index, player)
            else address(parts.`guardian$second`(), index - first.containerSize, player)
        }
        container is net.minecraft.world.entity.vehicle.AbstractMinecartContainer -> MinecartInventorySnapshotter.owner(container,player.serverLevel())?.let { ItemSlotAddress(it,index) }
        container is BlockEntity && container.level === player.serverLevel() -> {
            val pos = container.blockPos
            ItemSlotAddress(ItemSlotOwner.BlockContainer(ResourceId.parse(player.serverLevel().dimension().location().toString()), BlockPosition(pos.x, pos.y, pos.z)), index)
        }
        else -> null
    }
}
