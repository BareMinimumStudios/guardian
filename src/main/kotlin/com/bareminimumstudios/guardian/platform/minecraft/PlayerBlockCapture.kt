package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.ActionType
import com.bareminimumstudios.guardian.domain.BlockChangeSnapshot
import com.bareminimumstudios.guardian.domain.BlockPosition
import com.bareminimumstudios.guardian.domain.BlockStateSnapshot
import com.bareminimumstudios.guardian.domain.ChangeCause
import com.bareminimumstudios.guardian.domain.ResourceId
import com.bareminimumstudios.guardian.logging.BufferedLogPipeline
import com.bareminimumstudios.guardian.logging.SubmissionResult
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemPlacementContext
import net.minecraft.util.ActionResult
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import org.slf4j.LoggerFactory
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Server-thread capture boundary for the Step 2B player block vertical slice.
 *
 * Breaks use Fabric's AFTER event because it provides the old state and old block entity only
 * after Minecraft confirms the break. Placements are bracketed by a narrow BlockItem mixin.
 */
object PlayerBlockCapture {
    private val logger = LoggerFactory.getLogger("Guardian/BlockCapture")
    private val installed = AtomicBoolean(false)

    @Volatile
    private var pipelineProvider: () -> BufferedLogPipeline? = { null }

    @Volatile
    private var enabledProvider: () -> Boolean = { false }

    private val placementFrames = ThreadLocal.withInitial { ArrayDeque<PlacementFrame>() }

    private val submitted = AtomicLong()
    private val unchanged = AtomicLong()
    private val backpressure = AtomicLong()
    private val notRunning = AtomicLong()
    private val snapshotFailures = AtomicLong()

    fun install(
        pipelineProvider: () -> BufferedLogPipeline?,
        enabledProvider: () -> Boolean
    ) {
        this.pipelineProvider = pipelineProvider
        this.enabledProvider = enabledProvider

        if (installed.compareAndSet(false, true)) {
            PlayerBlockBreakHook.register()
        }
    }

    /** Called from BlockItemMixin after vanilla has resolved any specialized placement context. */
    @JvmStatic
    fun beginPlacement(context: ItemPlacementContext?) {
        val frames = placementFrames.get()
        if (context == null || !shouldCapture(context.world, context.player)) {
            frames.addLast(PlacementFrame.Ignored)
            return
        }

        val player = context.player ?: run {
            frames.addLast(PlacementFrame.Ignored)
            return
        }
        val pos = context.blockPos.toImmutable()

        val pending = runCatching {
            PendingPlacement(
                world = context.world,
                player = player,
                pos = pos,
                timestampEpochMillis = System.currentTimeMillis(),
                before = MinecraftBlockSnapshotter.snapshot(context.world, pos)
            )
        }.onFailure { throwable ->
            snapshotFailures.incrementAndGet()
            logger.error("Failed to snapshot block before player placement at {}", pos, throwable)
        }.getOrNull()

        frames.addLast(pending?.let(PlacementFrame::Pending) ?: PlacementFrame.Ignored)
    }

    /** Called from BlockItemMixin at BlockItem.place RETURN. */
    @JvmStatic
    fun finishPlacement(result: ActionResult?) {
        val frames = placementFrames.get()
        val frame = frames.pollLast() ?: return
        if (frames.isEmpty()) placementFrames.remove()

        val pending = (frame as? PlacementFrame.Pending)?.value ?: return
        if (result?.isAccepted != true) return

        runCatching {
            val after = MinecraftBlockSnapshotter.snapshot(pending.world, pending.pos)
            if (after == pending.before) {
                unchanged.incrementAndGet()
                return@runCatching
            }

            submit(
                BlockChangeSnapshot(
                    timestampEpochMillis = pending.timestampEpochMillis,
                    actor = MinecraftActorAdapter.player(pending.player),
                    dimension = dimensionId(pending.world),
                    position = pending.pos.toDomain(),
                    before = pending.before,
                    after = after,
                    cause = ChangeCause.PLAYER,
                    action = ActionType.BLOCK_PLACE
                )
            )
        }.onFailure { throwable ->
            snapshotFailures.incrementAndGet()
            logger.error("Failed to snapshot block after player placement at {}", pending.pos, throwable)
        }
    }

    fun metrics(): CaptureMetrics = CaptureMetrics(
        submitted = submitted.get(),
        unchanged = unchanged.get(),
        backpressure = backpressure.get(),
        notRunning = notRunning.get(),
        snapshotFailures = snapshotFailures.get()
    )

    @JvmStatic
    fun captureBreak(
        world: World,
        player: PlayerEntity,
        pos: BlockPos,
        state: net.minecraft.block.BlockState,
        blockEntity: net.minecraft.block.entity.BlockEntity?
    ) {
        if (!shouldCapture(world, player)) return

        runCatching {
            val before = MinecraftBlockSnapshotter.snapshot(world, state, blockEntity)
            val after = MinecraftBlockSnapshotter.snapshot(world, pos)
            if (before == after) {
                unchanged.incrementAndGet()
                return@runCatching
            }

            submit(
                BlockChangeSnapshot(
                    timestampEpochMillis = System.currentTimeMillis(),
                    actor = MinecraftActorAdapter.player(player),
                    dimension = dimensionId(world),
                    position = pos.toDomain(),
                    before = before,
                    after = after,
                    cause = ChangeCause.PLAYER,
                    action = ActionType.BLOCK_BREAK
                )
            )
        }.onFailure { throwable ->
            snapshotFailures.incrementAndGet()
            logger.error("Failed to capture player block break at {}", pos, throwable)
        }
    }

    private fun shouldCapture(world: World, player: PlayerEntity?): Boolean =
        enabledProvider() && !world.isClient && player != null

    private fun submit(snapshot: BlockChangeSnapshot) {
        when (pipelineProvider()?.submit(snapshot) ?: SubmissionResult.NOT_RUNNING) {
            SubmissionResult.ACCEPTED -> submitted.incrementAndGet()
            SubmissionResult.BACKPRESSURE -> {
                val count = backpressure.incrementAndGet()
                if (count == 1L || count and (count - 1L) == 0L) {
                    logger.error(
                        "Guardian audit queue is saturated; player block event was not queued (backpressure count={}). " +
                            "Increase performance.queueCapacity or investigate storage throughput.",
                        count
                    )
                }
            }
            SubmissionResult.NOT_RUNNING -> notRunning.incrementAndGet()
        }
    }

    private fun dimensionId(world: World): ResourceId = ResourceId.parse(world.registryKey.value.toString())

    private fun BlockPos.toDomain(): BlockPosition = BlockPosition(x, y, z)

    private sealed interface PlacementFrame {
        data object Ignored : PlacementFrame
        data class Pending(val value: PendingPlacement) : PlacementFrame
    }

    private data class PendingPlacement(
        val world: World,
        val player: PlayerEntity,
        val pos: BlockPos,
        val timestampEpochMillis: Long,
        val before: BlockStateSnapshot
    )
}
