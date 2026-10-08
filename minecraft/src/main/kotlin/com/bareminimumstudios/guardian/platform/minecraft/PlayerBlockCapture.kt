package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.ActionType
import com.bareminimumstudios.guardian.domain.BlockChangeSnapshot
import com.bareminimumstudios.guardian.domain.BlockPosition
import com.bareminimumstudios.guardian.domain.BlockStateSnapshot
import com.bareminimumstudios.guardian.domain.ChangeCause
import com.bareminimumstudios.guardian.domain.ResourceId
import com.bareminimumstudios.guardian.logging.BufferedLogPipeline
import com.bareminimumstudios.guardian.logging.SubmissionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.InteractionResult
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import org.slf4j.LoggerFactory
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Server-thread capture boundary for the Step 2B player block vertical slice.
 *
 * Breaks use Fabric's AFTER event because it provides the old state and old block entity only
 * after Minecraft confirms the break. Placements are bracketed by a narrow BlockItem mixin.
 */
object PlayerBlockCapture {
    private val logger = LoggerFactory.getLogger("Guardian/BlockCapture")

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

    }

    @JvmStatic
    fun enterPlacement() { placementFrames.get().addLast(PlacementFrame.Ignored) }

    @JvmStatic
    fun abortPlacement() {
        val frames = placementFrames.get()
        frames.pollLast()
        if (frames.isEmpty()) placementFrames.remove()
    }

    /** Called from BlockItemMixin after vanilla has resolved any specialized placement context. */
    @JvmStatic
    fun beginPlacement(context: BlockPlaceContext?) {
        val frames = placementFrames.get()
        // Replace this invocation's placeholder; early returns and exceptions stay balanced.
        check(frames.pollLast() != null) { "Placement capture requires an invocation frame" }
        if (context == null || !shouldCapture(context.level, context.player)) {
            frames.addLast(PlacementFrame.Ignored)
            return
        }

        val player = context.player ?: run {
            frames.addLast(PlacementFrame.Ignored)
            return
        }
        val pos = context.clickedPos.immutable()

        val pending = runCatching {
            PendingPlacement(
                world = context.level,
                player = player,
                pos = pos,
                timestampEpochMillis = System.currentTimeMillis(),
                before = MinecraftBlockSnapshotter.snapshot(context.level, pos)
            )
        }.onFailure { throwable ->
            snapshotFailures.incrementAndGet()
            logger.error("Failed to snapshot block before player placement at {}", pos, throwable)
        }.getOrNull()

        frames.addLast(pending?.let(PlacementFrame::Pending) ?: PlacementFrame.Ignored)
    }

    /** Called from BlockItemMixin at BlockItem.place RETURN. */
    @JvmStatic
    fun finishPlacement(result: InteractionResult?) {
        val frames = placementFrames.get()
        val frame = frames.pollLast() ?: return
        if (frames.isEmpty()) placementFrames.remove()

        val pending = (frame as? PlacementFrame.Pending)?.value ?: return
        if (result?.consumesAction() != true) return

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
        world: Level,
        player: Player,
        pos: BlockPos,
        state: net.minecraft.world.level.block.state.BlockState,
        blockEntity: net.minecraft.world.level.block.entity.BlockEntity?
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

    /** NeoForge capture begins before destruction and commits only after its accepted result. */
    @JvmStatic
    fun beginBreak(world: Level, player: Player, pos: BlockPos): BlockStateSnapshot? {
        if (!shouldCapture(world, player)) return null
        return runCatching { MinecraftBlockSnapshotter.snapshot(world, pos) }
            .onFailure { snapshotFailures.incrementAndGet(); logger.error("Failed to snapshot before player break at {}", pos, it) }
            .getOrNull()
    }

    @JvmStatic
    fun finishBreak(world: Level, player: Player, pos: BlockPos, before: BlockStateSnapshot?) {
        if (before == null || !shouldCapture(world, player)) return
        runCatching {
            val after = MinecraftBlockSnapshotter.snapshot(world, pos)
            if (before == after) { unchanged.incrementAndGet(); return@runCatching }
            submit(BlockChangeSnapshot(
                timestampEpochMillis = System.currentTimeMillis(),
                actor = MinecraftActorAdapter.player(player),
                dimension = dimensionId(world), position = pos.toDomain(),
                before = before, after = after, cause = ChangeCause.PLAYER, action = ActionType.BLOCK_BREAK
            ))
        }.onFailure { snapshotFailures.incrementAndGet(); logger.error("Failed to snapshot after player break at {}", pos, it) }
    }

    /** Accepted use of a door, trapdoor, gate, lever, or button; not item placement/open-menu packets. */
    @JvmStatic
    fun beginInteraction(world: Level, player: Player, pos: BlockPos): BlockStateSnapshot? {
        if (!shouldCapture(world, player) || com.bareminimumstudios.guardian.lookup.BlockInspector.isEnabled(player as? net.minecraft.server.level.ServerPlayer ?: return null)) return null
        val block = world.getBlockState(pos).block
        if (block !is net.minecraft.world.level.block.DoorBlock && block !is net.minecraft.world.level.block.TrapDoorBlock &&
            block !is net.minecraft.world.level.block.FenceGateBlock && block !is net.minecraft.world.level.block.LeverBlock && block !is net.minecraft.world.level.block.ButtonBlock) return null
        return runCatching { MinecraftBlockSnapshotter.snapshot(world, pos) }.onFailure { snapshotFailures.incrementAndGet(); logger.error("Failed to snapshot interaction at {}", pos, it) }.getOrNull()
    }

    @JvmStatic
    fun finishInteraction(world: Level, player: Player, pos: BlockPos, before: BlockStateSnapshot?, result: InteractionResult) {
        if (before == null || !result.consumesAction() || !shouldCapture(world, player)) return
        runCatching {
            val after = MinecraftBlockSnapshotter.snapshot(world, pos)
            if (before.blockId != after.blockId || before.properties == after.properties) return@runCatching
            submit(BlockChangeSnapshot(System.currentTimeMillis(), MinecraftActorAdapter.player(player), dimensionId(world), pos.toDomain(),
                before, after, ChangeCause.PLAYER, ActionType.BLOCK_CHANGE))
        }.onFailure { snapshotFailures.incrementAndGet(); logger.error("Failed to capture accepted interaction at {}", pos, it) }
    }

    private fun shouldCapture(world: Level, player: Player?): Boolean =
        enabledProvider() && !world.isClientSide && player != null

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

    private fun dimensionId(world: Level): ResourceId = ResourceId.parse(world.dimension().location().toString())

    private fun BlockPos.toDomain(): BlockPosition = BlockPosition(x, y, z)

    private sealed interface PlacementFrame {
        data object Ignored : PlacementFrame
        data class Pending(val value: PendingPlacement) : PlacementFrame
    }

    private data class PendingPlacement(
        val world: Level,
        val player: Player,
        val pos: BlockPos,
        val timestampEpochMillis: Long,
        val before: BlockStateSnapshot
    )
}
