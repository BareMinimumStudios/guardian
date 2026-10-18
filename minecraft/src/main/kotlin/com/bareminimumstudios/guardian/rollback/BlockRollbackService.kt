package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.permission.PermissionService
import com.bareminimumstudios.guardian.config.GuardianConfig
import com.bareminimumstudios.guardian.lookup.BlockHistoryService
import com.bareminimumstudios.guardian.platform.minecraft.MinecraftBlockRestorer
import com.bareminimumstudios.guardian.platform.minecraft.MinecraftBlockSnapshotter
import com.bareminimumstudios.guardian.storage.query.BlockLookupQuery
import com.bareminimumstudios.guardian.storage.query.BlockRollbackState
import com.bareminimumstudios.guardian.storage.query.StoredBlockChange
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.world.level.Level
import org.slf4j.LoggerFactory
import java.util.ArrayDeque

/**
 * First block-only rollback executor.
 *
 * Safety properties:
 * - lookup is asynchronous;
 * - one rollback session runs at a time;
 * - work is bounded per tick;
 * - only loaded chunks are touched;
 * - live state must equal the recorded AFTER state before mutation;
 * - rollback rows use ACTIVE -> PENDING -> ROLLED_BACK as a crash-recoverable journal.
 */
class BlockRollbackService(
    private val server: MinecraftServer,
    private val history: BlockHistoryService,
    private val config: GuardianConfig,
    private val permissions: PermissionService
) {
    private val logger = LoggerFactory.getLogger("Guardian/Rollback")
    private var active: Session? = null
    private var planning: Any? = null
    private var previewSession: PreviewSession? = null

    fun request(source: CommandSourceStack, query: BlockLookupQuery, description: String): Boolean = requestInternal(source, query, description, false)
    fun preview(source: CommandSourceStack, query: BlockLookupQuery, description: String): Boolean = requestInternal(source, query, description, true)

    private fun requestInternal(source: CommandSourceStack, query: BlockLookupQuery, description: String, previewOnly: Boolean): Boolean {
        if (!authorized(source)) return false
        if (active != null || planning != null || previewSession != null) {
            source.sendFailure(Component.literal("Guardian already has a block rollback in progress."))
            return false
        }

        val worldKey = source.level.dimension()
        val token = Any()
        planning = token
        val maxRecords = config.rollback.maxRecords.get()
        val bounded = query.copy(includeRolledBack = false, limit = (maxRecords + 1).coerceAtMost(10_000), offset = 0, oldestFirst = false)
        source.sendSystemMessage(Component.literal(if (previewOnly) "Guardian: checking block rollback preview; no blocks will change…" else "Guardian: planning block rollback…"))

        history.lookup(
            bounded,
            onSuccess = { rows ->
                if (planning !== token) return@lookup
                planning = null
                if (!authorized(source)) return@lookup
                if (rows.isEmpty()) {
                    source.sendSystemMessage(Component.literal("Guardian: no matching active block changes were found."))
                    return@lookup
                }
                if (rows.size > maxRecords) {
                    source.sendFailure(
                        Component.literal(
                            "Guardian refused this rollback because it matches more than $maxRecords rows. " +
                                "Use a smaller t:, r:, or u: filter."
                        )
                    )
                    return@lookup
                }
                if (previewOnly) {
                    previewSession = PreviewSession(source, worldKey, ArrayDeque(rows), description)
                    return@lookup
                }
                active = Session(
                    source = source,
                    worldKey = worldKey,
                    pendingRows = ArrayDeque(rows),
                    description = description,
                    total = rows.size
                )
                source.sendSystemMessage(Component.literal("Guardian: rollback queued for ${rows.size} block change(s)."))
            },
            onFailure = {
                if (planning !== token) return@lookup
                planning = null
                if (!authorized(source)) return@lookup
                source.sendFailure(Component.literal("Guardian block rollback lookup failed; see the server log."))
            }
        )
        return true
    }

    fun tick() {
        previewSession?.let { tickPreview(it); return }
        val session = active ?: return
        if (!authorized(session.source)) session.revoked = true
        if (session.revoked && session.phase == Phase.READY) { active = null; return }
        if (session.revoked && session.phase == Phase.APPLY) { releaseCurrentBatchAndAbort(session); return }
        when (session.phase) {
            Phase.READY -> beginNextBatch(session)
            Phase.APPLY -> applyClaimedBatch(session)
            Phase.WAITING_FOR_DATABASE -> Unit
            Phase.FINISHED -> finish(session)
        }
    }

    fun stop() {
        previewSession = null
        planning = null
        active = null
    }

    private fun tickPreview(session: PreviewSession) {
        if (previewSession !== session) return
        if (!authorized(session.source)) { previewSession = null; return }
        val world = server.getLevel(session.worldKey)
        if (world == null) {
            session.source.sendFailure(Component.literal("Guardian preview world is unavailable. No blocks or journal rows changed."))
            previewSession = null; return
        }
        repeat(minOf(config.rollback.blocksPerTick.get(), session.rows.size)) {
            if (!authorized(session.source)) { previewSession = null; return }
            val row = session.rows.removeFirst()
            val pos = row.snapshot.position
            var unavailable = BlockRollbackPreviewPlan.Outcome.UNLOADED
            val live = if (!world.chunkSource.hasChunk(pos.x shr 4, pos.z shr 4)) null else try {
                MinecraftBlockSnapshotter.snapshot(world, net.minecraft.core.BlockPos(pos.x, pos.y, pos.z))
            } catch (error: Exception) {
                unavailable = BlockRollbackPreviewPlan.Outcome.READ_FAILURE
                logger.warn("Could not read block for rollback preview at {}", pos, error); null
            }
            val supported = live != null && runCatching {
                com.bareminimumstudios.guardian.platform.minecraft.BlockStateSnapshotDecoder.decode(row.snapshot.before)
                row.snapshot.before.blockEntityData?.let { com.bareminimumstudios.guardian.platform.minecraft.MinecraftNbtPayloadCodec.decode(it) }
            }.isSuccess
            val result = session.plan.observe(row, live, unavailable, supported)
            if (result == BlockRollbackPreviewPlan.Outcome.WOULD_RESTORE && session.examples.size < 5) {
                session.examples.add("${pos.x}, ${pos.y}, ${pos.z}: ${row.snapshot.after.blockId} → ${row.snapshot.before.blockId}")
            }
        }
        if (session.rows.isNotEmpty()) return
        if (!authorized(session.source)) { previewSession = null; return }
        fun count(outcome: BlockRollbackPreviewPlan.Outcome) = session.plan.counts[outcome] ?: 0
        session.source.sendSystemMessage(Component.literal("Guardian block rollback preview: ${count(BlockRollbackPreviewPlan.Outcome.WOULD_RESTORE)} would restore, ${count(BlockRollbackPreviewPlan.Outcome.ALREADY_BEFORE)} already before, ${count(BlockRollbackPreviewPlan.Outcome.STATE_MISMATCH)} state mismatch, ${count(BlockRollbackPreviewPlan.Outcome.UNLOADED)} unloaded, ${count(BlockRollbackPreviewPlan.Outcome.BLOCKED_CHAIN)} blocked, ${count(BlockRollbackPreviewPlan.Outcome.UNSUPPORTED)} unsupported, ${count(BlockRollbackPreviewPlan.Outcome.READ_FAILURE)} read failures."))
        session.examples.forEach { session.source.sendSystemMessage(Component.literal(it)) }
        session.source.sendSystemMessage(Component.literal("Preview only; no blocks or journal rows changed. Apply separately with the same filters; live state may change. ${session.description}"))
        previewSession = null
    }

    private data class PreviewSession(
        val source: CommandSourceStack, val worldKey: ResourceKey<Level>,
        val rows: ArrayDeque<StoredBlockChange>, val description: String,
        val plan: BlockRollbackPreviewPlan = BlockRollbackPreviewPlan(), val examples: MutableList<String> = mutableListOf()
    )

    private fun beginNextBatch(session: Session) {
        if (session.pendingRows.isEmpty()) {
            session.phase = Phase.FINISHED
            return
        }

        val batchSize = config.rollback.blocksPerTick.get().coerceAtLeast(1)
        val rows = ArrayList<StoredBlockChange>(batchSize)
        val iterator = session.pendingRows.iterator()
        while (iterator.hasNext() && rows.size < batchSize) rows += iterator.next()

        session.currentBatch = rows
        session.phase = Phase.WAITING_FOR_DATABASE
        history.setRollbackState(
            rowIds = rows.map(StoredBlockChange::rowId),
            state = BlockRollbackState.PENDING,
            onSuccess = {
                if (active === session) {
                    if (!authorized(session.source)) session.revoked = true
                    session.phase = Phase.APPLY
                }
            },
            onFailure = {
                reportFailure(session, Component.literal("Guardian could not journal the rollback batch; no blocks from that batch were changed."))
                if (active === session) active = null
            }
        )
    }

    private fun applyClaimedBatch(session: Session) {
        val world = server.getLevel(session.worldKey)
        if (world == null) {
            reportFailure(session, Component.literal("Guardian rollback world is no longer available."))
            releaseCurrentBatchAndAbort(session)
            return
        }

        val successful = ArrayList<Long>()
        val release = ArrayList<Long>()

        for (row in session.currentBatch) {
            if (!authorized(session.source)) session.revoked = true
            if (session.revoked) {
                if (row.rollbackState == BlockRollbackState.ACTIVE) release += row.rowId
                continue
            }
            val snapshot = row.snapshot
            val pos = snapshot.position
            if (pos in session.blockedPositions) {
                session.skippedBlocked++
                release += row.rowId
                continue
            }
            if (!world.chunkSource.hasChunk(pos.x shr 4, pos.z shr 4)) {
                session.skippedUnloaded++
                session.blockedPositions += pos
                release += row.rowId
                continue
            }

            val live = try {
                MinecraftBlockSnapshotter.snapshot(world, net.minecraft.core.BlockPos(pos.x, pos.y, pos.z))
            } catch (throwable: Throwable) {
                logger.error("Failed to snapshot live block while rolling back row {}", row.rowId, throwable)
                session.failed++
                session.blockedPositions += pos
                release += row.rowId
                continue
            }

            when (BlockRollbackDecision.decide(live, row)) {
                // Recovery for a crash after world mutation but before the PENDING row was finalized.
                BlockRollbackDecision.ALREADY_APPLIED -> {
                    session.recoveredPending++
                    successful += row.rowId
                    continue
                }
                BlockRollbackDecision.SKIP_STATE_MISMATCH -> {
                    session.skippedMismatch++
                    session.blockedPositions += pos
                    release += row.rowId
                    continue
                }
                BlockRollbackDecision.APPLY -> Unit
            }

            val restored = MinecraftBlockRestorer.restore(world, pos, snapshot.before)
            if (!restored.success) {
                session.failed++
                session.blockedPositions += pos
                release += row.rowId
                logger.warn("Failed to restore block rollback row {}: {}", row.rowId, restored.reason)
                continue
            }

            val verified = runCatching {
                MinecraftBlockSnapshotter.snapshot(world, net.minecraft.core.BlockPos(pos.x, pos.y, pos.z))
            }.getOrNull()
            if (verified != snapshot.before) {
                // Best-effort compensation: if the target could not be reproduced exactly, restore the live state we replaced.
                val compensation = MinecraftBlockRestorer.restore(world, pos, live)
                session.failed++
                session.blockedPositions += pos
                release += row.rowId
                logger.error(
                    "Rollback row {} did not verify after restoration; compensation success={}",
                    row.rowId,
                    compensation.success
                )
                continue
            }

            session.applied++
            successful += row.rowId
        }

        session.phase = Phase.WAITING_FOR_DATABASE
        finalizeBatch(session, successful, release)
    }

    private fun finalizeBatch(session: Session, successful: List<Long>, release: List<Long>) {
        fun releaseSkipped() {
            if (release.isEmpty()) {
                completeBatch(session)
                return
            }
            history.setRollbackState(
                release,
                BlockRollbackState.ACTIVE,
                onSuccess = { completeBatch(session) },
                onFailure = {
                    reportFailure(session,
                        Component.literal("Guardian restored the safe rows, but could not release some pending journal rows. They can be reconciled on a later rollback.")
                    )
                    completeBatch(session)
                }
            )
        }

        if (successful.isEmpty()) {
            releaseSkipped()
            return
        }

        history.setRollbackState(
            successful,
            BlockRollbackState.ROLLED_BACK,
            onSuccess = { releaseSkipped() },
            onFailure = {
                // Keep them PENDING. On the next rollback attempt, live==before will reconcile them without reapplying.
                reportFailure(session,
                    Component.literal("Guardian changed a rollback batch but could not finalize its journal rows. They remain pending for crash-safe reconciliation.")
                )
                releaseSkipped()
            }
        )
    }

    private fun completeBatch(session: Session) {
        if (active !== session) return
        repeat(session.currentBatch.size) { session.pendingRows.removeFirst() }
        session.currentBatch = emptyList()
        if (!authorized(session.source)) session.revoked = true
        if (session.revoked) { active = null; return }
        session.phase = if (session.pendingRows.isEmpty()) Phase.FINISHED else Phase.READY
    }

    private fun releaseCurrentBatchAndAbort(session: Session) {
        val ids = session.currentBatch.filter { it.rollbackState == BlockRollbackState.ACTIVE }.map(StoredBlockChange::rowId)
        if (ids.isEmpty()) {
            active = null
            return
        }
        session.phase = Phase.WAITING_FOR_DATABASE
        history.setRollbackState(
            ids,
            BlockRollbackState.ACTIVE,
            onSuccess = { if (active === session) active = null },
            onFailure = { if (active === session) active = null }
        )
    }

    private fun finish(session: Session) {
        if (active !== session) return
        if (session.revoked || !authorized(session.source)) { active = null; return }
        session.source.sendSystemMessage(
            Component.literal(
                "Guardian rollback complete: ${session.applied} applied, ${session.recoveredPending} recovered, " +
                    "${session.skippedMismatch} skipped (newer state), ${session.skippedBlocked} skipped (blocked chain), " +
                    "${session.skippedUnloaded} skipped (unloaded), ${session.failed} failed. ${session.description}"
            )
        )
        active = null
    }

    private fun authorized(source: CommandSourceStack): Boolean =
        runCatching { permissions.has(source, "guardian.rollback", 3) }.getOrDefault(false)

    private fun reportFailure(session: Session, message: Component) {
        if (active === session && !session.revoked && authorized(session.source)) session.source.sendFailure(message)
    }

    private data class Session(
        val source: CommandSourceStack,
        val worldKey: ResourceKey<Level>,
        val pendingRows: ArrayDeque<StoredBlockChange>,
        val description: String,
        val total: Int,
        var currentBatch: List<StoredBlockChange> = emptyList(),
        var phase: Phase = Phase.READY,
        var revoked: Boolean = false,
        var applied: Int = 0,
        var recoveredPending: Int = 0,
        var skippedMismatch: Int = 0,
        var skippedBlocked: Int = 0,
        var skippedUnloaded: Int = 0,
        var failed: Int = 0,
        val blockedPositions: MutableSet<com.bareminimumstudios.guardian.domain.BlockPosition> = HashSet()
    )

    private enum class Phase {
        READY,
        WAITING_FOR_DATABASE,
        APPLY,
        FINISHED
    }
}
