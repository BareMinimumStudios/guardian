package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.logging.BufferedLogPipeline
import com.bareminimumstudios.guardian.logging.SubmissionResult
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
import java.util.concurrent.atomic.AtomicLong

/** First Step 4 slice: server-accepted menu clicks backed by block containers and player inventory. */
object PlayerContainerCapture {
    private val logger = LoggerFactory.getLogger("Guardian/ContainerCapture")
    @Volatile private var pipeline: () -> BufferedLogPipeline? = { null }
    @Volatile private var enabled: () -> Boolean = { false }
    private val submitted = AtomicLong(); private val failures = AtomicLong(); private val backpressure = AtomicLong()
    fun install(pipeline: () -> BufferedLogPipeline?, enabled: () -> Boolean) { this.pipeline = pipeline; this.enabled = enabled }
    fun status() = "Container audit: submitted=${submitted.get()} failures=${failures.get()} backpressure=${backpressure.get()}"

    @JvmStatic fun begin(menu: AbstractContainerMenu, type: ClickType, player: Player): ContainerTransactionCorrelation? {
        if (!enabled() || player !is ServerPlayer || player.containerMenu !== menu) return null
        check(player.server.isSameThread) { "Container snapshot capture must run on the server thread" }
        return runCatching {
            val before = snapshot(menu, player) ?: return@runCatching null
            ContainerTransactionCorrelation(MinecraftActorAdapter.player(player), menu.containerId, ContainerAction.valueOf(type.name), before)
        }.onFailure(::failed).getOrNull()
    }

    @JvmStatic fun finish(pending: ContainerTransactionCorrelation?, menu: AbstractContainerMenu, player: Player) {
        if (pending == null || player !is ServerPlayer || player.containerMenu !== menu) return
        runCatching {
            val after = snapshot(menu, player) ?: return@runCatching
            val transaction = pending.finish(after, true) ?: return@runCatching
            ContainerChangesCodec.encode(transaction.changes) // Check persistence budget before queueing.
            when (pipeline()?.submit(ContainerAuditEntry(transaction)) ?: SubmissionResult.NOT_RUNNING) {
                SubmissionResult.ACCEPTED -> submitted.incrementAndGet()
                SubmissionResult.BACKPRESSURE -> {
                    val count = backpressure.incrementAndGet()
                    if (count == 1L || count and (count - 1L) == 0L) logger.error("Container audit queue is saturated; unqueued transactions={}", count)
                }
                SubmissionResult.NOT_RUNNING -> failed(IllegalStateException("Container audit writer is unavailable"))
            }
        }.onFailure(::failed)
    }

    private fun failed(error: Throwable) {
        val count = failures.incrementAndGet()
        if (count == 1L || count and (count - 1L) == 0L) logger.error("Unable to capture container transaction; failures={}", count, error)
    }

    private fun snapshot(menu: AbstractContainerMenu, player: ServerPlayer): InventorySnapshot? {
        if (menu.slots.size >= ContainerChangesCodec.MAX_SLOTS) return null
        val slots = LinkedHashMap<ItemSlotAddress, ItemStackSnapshot>()
        for (index in 0 until player.inventory.containerSize) {
            slots[ItemSlotAddress(ItemSlotOwner.PlayerInventory(player.uuid), index)] = MinecraftItemSnapshotter.capture(player.inventory.getItem(index), player.registryAccess())
        }
        var hasBlockContainer = false
        for (slot in menu.slots) {
            val address = address(slot.container, slot.containerSlot, player) ?: return null
            if (address.owner is ItemSlotOwner.BlockContainer) hasBlockContainer = true
            if (address.owner is ItemSlotOwner.PlayerInventory) continue
            require(address !in slots) { "Menu aliases a logical inventory slot" }
            slots[address] = MinecraftItemSnapshotter.capture(slot.item, player.registryAccess())
        }
        if (!hasBlockContainer) return null
        slots[ItemSlotAddress(ItemSlotOwner.Cursor(player.uuid), 0)] = MinecraftItemSnapshotter.capture(menu.carried, player.registryAccess())
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
