package com.bareminimumstudios.guardian.rollback
import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import kotlin.test.*
class RetainedReadAuthorityTest {
    private val owner=ItemSlotOwner.PlayerInventory(UUID.randomUUID())
    private val journal=object:ItemRollbackJournal {
        override fun itemRollback(operationId:UUID):ItemRollbackRecord?=null
        override fun transitionItemRollback(operationId:UUID,expected:ItemRollbackPhase,next:ItemRollbackPhase)=false
        override fun prepareItemRollback(operationId:UUID,createdAt:Long,newestFirst:List<ContainerTransactionSnapshot>):ItemRollbackRecord=error("Unexpected prepare")
        override fun unfinishedItemRollbacks(limit:Int)=emptyList<ItemRollbackSummary>()
    }
    @Test fun revokedRetainedLeaseIsReadAuthorityAndNeverWriteAuthority(){
        val registry=ItemOwnerCoordination();val lease=assertNotNull(registry.acquire(UUID.randomUUID(),listOf(owner)));val worker=ItemRollbackJournalWorker(journal)
        val hold=lease.retainUntilJournalReconciled(worker);lease.close();worker.close()
        assertTrue(lease.isRetained(setOf(owner)));assertFalse(lease.isCurrent(setOf(owner)));assertFalse(registry.allowsMutation(owner,lease));assertFalse(registry.allowsMutation(owner))
        hold.releaseAfterReconciliation();assertFalse(lease.isRetained(setOf(owner)));assertTrue(registry.allowsMutation(owner))
    }
    @Test fun unretainedLeaseCannotAuthorizeReadOnlyRecovery(){val registry=ItemOwnerCoordination();val lease=assertNotNull(registry.acquire(UUID.randomUUID(),listOf(owner)));assertFalse(lease.isRetained(setOf(owner)));lease.close()}
    @Test fun stoppedRegistryAndWrongOwnerSetRefuseReadAuthority(){val registry=ItemOwnerCoordination();val lease=assertNotNull(registry.acquire(UUID.randomUUID(),listOf(owner)));val worker=ItemRollbackJournalWorker(journal);val hold=lease.retainUntilJournalReconciled(worker);assertFalse(lease.isRetained(emptySet()));registry.stop();worker.close();assertFalse(lease.isRetained(setOf(owner)));hold.releaseAfterReconciliation()}
}
