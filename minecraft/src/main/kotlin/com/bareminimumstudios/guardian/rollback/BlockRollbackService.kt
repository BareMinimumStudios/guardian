package com.bareminimumstudios.guardian.rollback

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
    private val config: GuardianConfig
) {
    private val logger = LoggerFactory.getLogger("Guardian/Rollback")
    private var active: Session? = null
    private var planning: Boolean = false

    fun request(source: CommandSourceStack, query: BlockLookupQuery, description: String): Boolean {
        if (active != null || planning) {
            source.sendFailure(Component.literal("Guardian already has a block rollback in progress."))
            return false
        }

        planning = true
        val maxRecords = config.rollback.maxRecords.get()
        val bounded = query.copy(includeRolledBack = false, limit = (maxRecords + 1).coerceAtMost(10_000))
        source.sendSystemMessage(Component.literal("Guardian: planning block rollback…"))

        history.lookup(
            bounded,
            onSuccess = { rows ->
                planning = false
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
                active = Session(
                    source = source,
                    worldKey = source.level.dimension(),
                    pendingRows = ArrayDeque(rows),
                    description = description,
                    total = rows.size
                )
                source.sendSystemMessage(Component.literal("Guardian: rollback queued for ${rows.size} block change(s)."))
            },
            onFailure = {
                planning = false
                source.sendFailure(Component.literal("Guardian block rollback lookup failed; see the server log."))
            }
        )
        return true
    }

    fun tick() {
        val session = active ?: return
        when (session.phase) {
            Phase.READY -> beginNextBatch(session)
            Phase.APPLY -> applyClaimedBatch(session)
            Phase.WAITING_FOR_DATABASE -> Unit
            Phase.FINISHED -> finish(session)
        }
    }

    fun stop() {
        planning = false
        active = null
    }

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
            onSuccess = { session.phase = Phase.APPLY },
            onFailure = {
                session.source.sendFailure(Component.literal("Guardian could not journal the rollback batch; no blocks from that batch were changed."))
                active = null
            }
        )
    }

    private fun applyClaimedBatch(session: Session) {
        val world = server.getLevel(session.worldKey)
        if (world == null) {
            session.source.sendFailure(Component.literal("Guardian rollback world is no longer available."))
            releaseCurrentBatchAndAbort(session)
            return
        }

        val successful = ArrayList<Long>()
        val release = ArrayList<Long>()

        for (row in session.currentBatch) {
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
                    session.source.sendFailure(
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
                session.source.sendFailure(
                    Component.literal("Guardian changed a rollback batch but could not finalize its journal rows. They remain pending for crash-safe reconciliation.")
                )
                releaseSkipped()
            }
        )
    }

    private fun completeBatch(session: Session) {
        repeat(session.currentBatch.size) { session.pendingRows.removeFirst() }
        session.currentBatch = emptyList()
        session.phase = if (session.pendingRows.isEmpty()) Phase.FINISHED else Phase.READY
    }

    private fun releaseCurrentBatchAndAbort(session: Session) {
        val ids = session.currentBatch.map(StoredBlockChange::rowId)
        if (ids.isEmpty()) {
            active = null
            return
        }
        session.phase = Phase.WAITING_FOR_DATABASE
        history.setRollbackState(
            ids,
            BlockRollbackState.ACTIVE,
            onSuccess = { active = null },
            onFailure = { active = null }
        )
    }

    private fun finish(session: Session) {
        session.source.sendSystemMessage(
            Component.literal(
                "Guardian rollback complete: ${session.applied} applied, ${session.recoveredPending} recovered, " +
                    "${session.skippedMismatch} skipped (newer state), ${session.skippedBlocked} skipped (blocked chain), " +
                    "${session.skippedUnloaded} skipped (unloaded), ${session.failed} failed. ${session.description}"
            )
        )
        active = null
    }

    private data class Session(
        val source: CommandSourceStack,
        val worldKey: ResourceKey<Level>,
        val pendingRows: ArrayDeque<StoredBlockChange>,
        val description: String,
        val total: Int,
        var currentBatch: List<StoredBlockChange> = emptyList(),
        var phase: Phase = Phase.READY,
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
