package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.storage.query.BlockRollbackState
import com.bareminimumstudios.guardian.storage.query.StoredBlockChange

/** Direction and journal ownership for block history replay. Original audit snapshots are never edited. */
enum class BlockHistoryOperation(val command: String, val permission: String, val start: BlockRollbackState, val pending: BlockRollbackState, val completed: BlockRollbackState, val oldestFirst: Boolean) {
    ROLLBACK("rollback", "guardian.rollback", BlockRollbackState.ACTIVE, BlockRollbackState.PENDING, BlockRollbackState.ROLLED_BACK, false),
    RESTORE("restore", "guardian.restore", BlockRollbackState.ROLLED_BACK, BlockRollbackState.RESTORE_PENDING, BlockRollbackState.ACTIVE, true);

    val eligibleStates: Set<BlockRollbackState> get() = setOf(start, pending)
    fun view(row: StoredBlockChange): StoredBlockChange = if (this == ROLLBACK) row else row.copy(snapshot = row.snapshot.copy(before = row.snapshot.after, after = row.snapshot.before))
}
