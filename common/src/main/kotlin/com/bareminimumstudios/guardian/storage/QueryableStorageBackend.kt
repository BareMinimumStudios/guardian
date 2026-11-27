package com.bareminimumstudios.guardian.storage

import com.bareminimumstudios.guardian.domain.ContainerTransactionSnapshot
import com.bareminimumstudios.guardian.domain.EntityAuditEvent
import com.bareminimumstudios.guardian.domain.FluidAuditEvent
import com.bareminimumstudios.guardian.domain.PlayerActivity
import com.bareminimumstudios.guardian.rollback.ContainerHistoryGuard
import com.bareminimumstudios.guardian.storage.purge.AuditPurgePlan
import com.bareminimumstudios.guardian.storage.purge.AuditPurgeResult
import com.bareminimumstudios.guardian.storage.purge.PurgeScope
import com.bareminimumstudios.guardian.storage.query.ContainerLookupQuery
import com.bareminimumstudios.guardian.storage.query.EntityLookupQuery
import com.bareminimumstudios.guardian.storage.query.FluidLookupQuery
import com.bareminimumstudios.guardian.storage.query.PlayerActivityQuery
import com.bareminimumstudios.guardian.storage.query.BlockLookupQuery
import com.bareminimumstudios.guardian.storage.query.BlockRollbackState
import com.bareminimumstudios.guardian.storage.query.StoredBlockChange

interface QueryableStorageBackend : StorageBackend {
    fun lookupBlocks(query: BlockLookupQuery): List<StoredBlockChange>

    /** Updates the rollback journal state for the supplied row IDs. */
    fun setBlockRollbackState(rowIds: Collection<Long>, state: BlockRollbackState): Int

    fun lookupContainers(query: ContainerLookupQuery): List<ContainerTransactionSnapshot>

    /** Null means this backend cannot establish persistent rollback history safety. */
    fun guardContainerHistory(rows: List<ContainerTransactionSnapshot>): ContainerHistoryGuard? = null

    fun lookupActivity(query: PlayerActivityQuery): List<PlayerActivity> = throw UnsupportedOperationException("Player activity history unavailable")

    fun previewPurge(cutoff: Long, limit: Int = 1000): AuditPurgePlan = throw UnsupportedOperationException("Purge unavailable")
    fun previewPurge(cutoff: Long, limit: Int, scope: PurgeScope): AuditPurgePlan {
        require(scope.global) { "Scoped purge unavailable for this backend" }
        return previewPurge(cutoff, limit)
    }
    fun applyPurge(plan: AuditPurgePlan, permitted: () -> Boolean): AuditPurgeResult = throw UnsupportedOperationException("Purge unavailable")

    fun lookupEntities(query: EntityLookupQuery): List<EntityAuditEvent> = throw UnsupportedOperationException("Entity history unavailable")

    fun lookupFluids(query: FluidLookupQuery): List<FluidAuditEvent> = throw UnsupportedOperationException("Fluid history unavailable")

    fun health(): StorageHealth
}
