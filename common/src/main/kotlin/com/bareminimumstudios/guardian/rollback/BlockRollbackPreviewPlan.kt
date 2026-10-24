package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.query.StoredBlockChange

/** Direction-ordered simulation only. Contains neither a world writer nor a journal writer. */
class BlockRollbackPreviewPlan(private val operation: BlockHistoryOperation = BlockHistoryOperation.ROLLBACK) {
    enum class Outcome { WOULD_RESTORE, ALREADY_BEFORE, STATE_MISMATCH, UNLOADED, BLOCKED_CHAIN, UNSUPPORTED, READ_FAILURE }
    private val simulated = mutableMapOf<BlockPosition, BlockStateSnapshot>()
    private val blocked = mutableSetOf<BlockPosition>()
    private val totals = mutableMapOf<Outcome, Int>()
    val counts: Map<Outcome, Int> get() = totals.toMap()
    fun observe(row: StoredBlockChange, live: BlockStateSnapshot?, unavailable: Outcome = Outcome.UNLOADED, targetSupported: Boolean = true): Outcome {
        require(unavailable == Outcome.UNLOADED || unavailable == Outcome.READ_FAILURE)
        val pos = row.snapshot.position
        val outcome = when {
            pos in blocked -> Outcome.BLOCKED_CHAIN
            live == null -> unavailable
            else -> when (BlockRollbackDecision.decide(simulated[pos] ?: live, operation.view(row))) {
                BlockRollbackDecision.ALREADY_APPLIED -> Outcome.ALREADY_BEFORE
                BlockRollbackDecision.SKIP_STATE_MISMATCH -> Outcome.STATE_MISMATCH
                BlockRollbackDecision.APPLY -> if (targetSupported) Outcome.WOULD_RESTORE else Outcome.UNSUPPORTED
            }
        }
        if (outcome == Outcome.WOULD_RESTORE || outcome == Outcome.ALREADY_BEFORE) simulated[pos] = operation.view(row).snapshot.before
        else blocked.add(pos)
        totals[outcome] = (totals[outcome] ?: 0) + 1
        return outcome
    }
}
