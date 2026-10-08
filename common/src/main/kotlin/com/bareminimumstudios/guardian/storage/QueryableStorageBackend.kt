package com.bareminimumstudios.guardian.storage

import com.bareminimumstudios.guardian.storage.query.BlockLookupQuery
import com.bareminimumstudios.guardian.storage.query.BlockRollbackState
import com.bareminimumstudios.guardian.storage.query.StoredBlockChange

interface QueryableStorageBackend : StorageBackend {
    fun lookupBlocks(query: BlockLookupQuery): List<StoredBlockChange>

    /** Updates the rollback journal state for the supplied row IDs. */
    fun setBlockRollbackState(rowIds: Collection<Long>, state: BlockRollbackState): Int

    fun lookupContainers(query: com.bareminimumstudios.guardian.storage.query.ContainerLookupQuery): List<com.bareminimumstudios.guardian.domain.ContainerTransactionSnapshot>

    /** Null means this backend cannot establish persistent rollback history safety. */
    fun guardContainerHistory(rows: List<com.bareminimumstudios.guardian.domain.ContainerTransactionSnapshot>): com.bareminimumstudios.guardian.rollback.ContainerHistoryGuard? = null

    fun health(): StorageHealth
}
