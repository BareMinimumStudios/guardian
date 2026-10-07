package com.bareminimumstudios.guardian.storage

import com.bareminimumstudios.guardian.storage.query.BlockLookupQuery
import com.bareminimumstudios.guardian.storage.query.BlockRollbackState
import com.bareminimumstudios.guardian.storage.query.StoredBlockChange

interface QueryableStorageBackend : StorageBackend {
    fun lookupBlocks(query: BlockLookupQuery): List<StoredBlockChange>

    /** Updates the rollback journal state for the supplied row IDs. */
    fun setBlockRollbackState(rowIds: Collection<Long>, state: BlockRollbackState): Int

    fun health(): StorageHealth
}
