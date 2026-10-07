package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.BlockStateSnapshot
import com.bareminimumstudios.guardian.storage.query.StoredBlockChange

/**
 * Pure rollback reconciliation decision.
 *
 * A row can be applied only when the live block is still the state Guardian recorded after
 * that change. If the live block already equals the recorded before state, a previously
 * PENDING row is safe to finalize without mutating the world again. Anything else is treated
 * as newer/untracked state and is never overwritten by this rollback.
 */
enum class BlockRollbackDecision {
    APPLY,
    ALREADY_APPLIED,
    SKIP_STATE_MISMATCH;

    companion object {
        fun decide(live: BlockStateSnapshot, row: StoredBlockChange): BlockRollbackDecision =
            when (live) {
                row.snapshot.before -> ALREADY_APPLIED
                row.snapshot.after -> APPLY
                else -> SKIP_STATE_MISMATCH
            }
    }
}
