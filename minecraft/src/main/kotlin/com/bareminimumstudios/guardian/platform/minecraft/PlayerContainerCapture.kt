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

    class Pending internal constructor(
        internal val playerId: UUID,
        internal val menu: AbstractContainerMenu,
        internal val includeContainers: Boolean,
        internal val requireEmptyCrafting: Boolean,
        internal val correlation: ContainerTransactionCorrelation,
        private val lease: ActionCaptureScope.Lease
    ) : AutoCloseable {
        override fun close() = lease.close()
    }

    @JvmStatic fun begin(menu: AbstractContainerMenu, slot: Int, type: ClickType, player: Player): Pending? {
        if (player !is ServerPlayer) return null
        if (menu === player.inventoryMenu) {
            if (!MinecraftInventorySnapshotter.acceptsInventoryClick(menu, player.inventory, slot)) return null
            return begin(menu, ContainerAction.valueOf(type.name), player, false, true)
        }
        return begin(menu, ContainerAction.valueOf(type.name), player, true)
    }

    @JvmStatic fun beginClose(player: ServerPlayer): Pending? {
        val inventoryMenu = player.containerMenu === player.inventoryMenu
        return begin(player.containerMenu, ContainerAction.CLOSE, player, !inventoryMenu, inventoryMenu)
    }

    @JvmStatic fun beginCreative(slot: net.minecraft.world.inventory.Slot, player: ServerPlayer): Pending? {
        if (!MinecraftInventorySnapshotter.isPlayerSlot(slot, player.inventory)) return null
        return beginPlayerAction(player, ContainerAction.CREATIVE_SET)
    }

    @JvmStatic fun beginPlayerAction(player: ServerPlayer, action: ContainerAction): Pending? = begin(player.containerMenu, action, player, false)

    private fun begin(menu: AbstractContainerMenu, action: ContainerAction, player: Player, includeContainers: Boolean, requireEmptyCrafting: Boolean = false): Pending? {
        if (!enabled() || player !is ServerPlayer || player.containerMenu !== menu || !player.server.isSameThread) return null
        val lease = scope.enter(player.uuid) ?: return null
        var retained = false
        try {
            return runCatching {
                val before = snapshot(menu, player, includeContainers, requireEmptyCrafting) ?: return@runCatching null
                Pending(player.uuid, menu, includeContainers, requireEmptyCrafting, ContainerTransactionCorrelation(MinecraftActorAdapter.player(player), menu.containerId, action, before), lease).also { retained = true }
            }.onFailure(::failed).getOrNull()
        } finally { if (!retained) lease.close() }
    }

    @JvmStatic fun finish(pending: Pending?, player: Player) {
        if (pending == null || player !is ServerPlayer || pending.playerId != player.uuid) return
        runCatching {
            // Read the original menu even after doCloseContainer switches back to inventoryMenu.
            val after = snapshot(pending.menu, player, pending.includeContainers, pending.requireEmptyCrafting) ?: return@runCatching
            val transaction = pending.correlation.finish(after, true) ?: return@runCatching
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

    private fun failed(error: Throwable) {
        val count = failures.incrementAndGet()
        if (count == 1L || count and (count - 1L) == 0L) logger.error("Unable to capture item transaction; failures={}", count, error)
    }

    private fun snapshot(menu: AbstractContainerMenu, player: ServerPlayer, includeContainers: Boolean, requireEmptyCrafting: Boolean): InventorySnapshot? {
        if (includeContainers && menu.slots.size >= ContainerChangesCodec.MAX_SLOTS) return null
        if (requireEmptyCrafting && !MinecraftInventorySnapshotter.supportsInventoryMenu(menu, player.inventory)) return null
        val slots = LinkedHashMap(MinecraftInventorySnapshotter.capturePlayer(menu, player.inventory, player.uuid, player.registryAccess()).slots)
        if (includeContainers) {
            var hasBlockContainer = false
            for (slot in menu.slots) {
                val address = address(slot.container, slot.containerSlot, player) ?: return null
                if (address.owner is ItemSlotOwner.BlockContainer) hasBlockContainer = true
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
        container is BlockEntity && container.level === player.serverLevel() -> {
            val pos = container.blockPos
            ItemSlotAddress(ItemSlotOwner.BlockContainer(ResourceId.parse(player.serverLevel().dimension().location().toString()), BlockPosition(pos.x, pos.y, pos.z)), index)
        }
        else -> null
    }
}
