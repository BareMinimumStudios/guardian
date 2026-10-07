package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.logging.BufferedLogPipeline
import com.bareminimumstudios.guardian.logging.SubmissionResult
import com.bareminimumstudios.guardian.logging.container.ActionCaptureScope
import com.bareminimumstudios.guardian.logging.container.HopperTransferCorrelation
import com.bareminimumstudios.guardian.storage.codec.ContainerChangesCodec
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.HopperBlock
import net.minecraft.world.level.block.entity.Hopper
import net.minecraft.world.level.block.entity.HopperBlockEntity
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Observes complete push/pull attempts, including NeoForge's capability fast path. */
object HopperTransferCapture {
    private val logger = LoggerFactory.getLogger("Guardian/HopperCapture")
    @Volatile private var pipeline: () -> BufferedLogPipeline? = { null }
    @Volatile private var enabled: () -> Boolean = { false }
    private val scope = ActionCaptureScope()
    // This key only scopes nested server-thread operations. Persisted attribution is a system actor.
    private val scopeKey = UUID(0, 0)
    private val submitted = AtomicLong(); private val failures = AtomicLong(); private val backpressure = AtomicLong()
    fun install(pipeline: () -> BufferedLogPipeline?, enabled: () -> Boolean) { this.pipeline = pipeline; this.enabled = enabled }
    fun status() = "Hopper audit: submitted=${submitted.get()} failures=${failures.get()} backpressure=${backpressure.get()}"

    class Pending internal constructor(internal val level: ServerLevel, internal val source: Container, internal val destination: Container,
        internal val correlation: HopperTransferCorrelation, private val lease: ActionCaptureScope.Lease) : AutoCloseable {
        override fun close() = lease.close()
    }

    @JvmStatic fun beginPush(level: Level, hopper: HopperBlockEntity): Pending? {
        if (!ready(level)) return null
        val serverLevel = level as ServerLevel
        return runCatching {
            val position = hopper.blockPos.relative(hopper.blockState.getValue(HopperBlock.FACING))
            val destination = MinecraftBlockContainerSnapshotter.resolve(serverLevel, position) ?: return@runCatching null
            begin(serverLevel, hopper, destination)
        }.onFailure(::failed).getOrNull()
    }

    @JvmStatic fun beginPull(level: Level, hopper: Hopper): Pending? {
        if (!ready(level) || hopper !is HopperBlockEntity) return null
        val serverLevel = level as ServerLevel
        return runCatching {
            val source = MinecraftBlockContainerSnapshotter.resolve(serverLevel, hopper.blockPos.above()) ?: return@runCatching null
            begin(serverLevel, source, hopper)
        }.onFailure(::failed).getOrNull()
    }

    private fun ready(level: Level) = enabled() && level is ServerLevel && level.server.isSameThread

    private fun begin(level: ServerLevel, source: Container, destination: Container): Pending? {
        val lease = scope.enter(scopeKey) ?: return null
        var retained = false
        try {
            val beforeSource = MinecraftBlockContainerSnapshotter.capture(source, level) ?: return null
            val beforeDestination = MinecraftBlockContainerSnapshotter.capture(destination, level) ?: return null
            val sourceOwners = beforeSource.slots.keys.map { it.owner as ItemSlotOwner.BlockContainer }.toSet()
            val destinationOwners = beforeDestination.slots.keys.map { it.owner as ItemSlotOwner.BlockContainer }.toSet()
            if (sourceOwners.intersect(destinationOwners).isNotEmpty()) return null
            val before = merge(beforeSource, beforeDestination)
            return Pending(level, source, destination, HopperTransferCorrelation(before, sourceOwners, destinationOwners), lease).also { retained = true }
        } finally { if (!retained) lease.close() }
    }

    private fun merge(source: InventorySnapshot, destination: InventorySnapshot): InventorySnapshot {
        require(source.slots.size + destination.slots.size <= ContainerChangesCodec.MAX_SLOTS)
        return InventorySnapshot(source.slots + destination.slots)
    }

    @JvmStatic fun finish(pending: Pending?) {
        if (pending == null) return
        runCatching {
            val source = checkNotNull(MinecraftBlockContainerSnapshotter.capture(pending.source, pending.level)) { "Hopper source topology changed" }
            val destination = checkNotNull(MinecraftBlockContainerSnapshotter.capture(pending.destination, pending.level)) { "Hopper destination topology changed" }
            val transaction = pending.correlation.finish(merge(source, destination)) ?: return@runCatching
            ContainerChangesCodec.encode(transaction.changes)
            when (pipeline()?.submit(ContainerAuditEntry(transaction)) ?: SubmissionResult.NOT_RUNNING) {
                SubmissionResult.ACCEPTED -> submitted.incrementAndGet()
                SubmissionResult.BACKPRESSURE -> {
                    val count = backpressure.incrementAndGet()
                    if (count == 1L || count and (count - 1L) == 0L) logger.error("Hopper audit queue is saturated; unqueued transactions={}", count)
                }
                SubmissionResult.NOT_RUNNING -> failed(IllegalStateException("Hopper audit writer is unavailable"))
            }
        }.onFailure(::failed)
    }

    private fun failed(error: Throwable) {
        val count = failures.incrementAndGet()
        if (count == 1L || count and (count - 1L) == 0L) logger.error("Unable to capture hopper transaction; failures={}", count, error)
    }
}
