package com.bareminimumstudios.guardian.storage.query

import com.bareminimumstudios.guardian.domain.BlockChangeSnapshot

data class StoredBlockChange(
    val rowId: Long,
    val snapshot: BlockChangeSnapshot,
    val rollbackState: BlockRollbackState
) {
    val rolledBack: Boolean
        get() = rollbackState == BlockRollbackState.ROLLED_BACK
}
