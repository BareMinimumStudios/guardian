package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.*
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.*
import kotlin.test.*

/** Real SQLite protocol; synthetic complete container images, not live Minecraft acceptance. */
class ProtectedItemOperationTest {
    @Test fun protectedScopePersistsImagesBeforeWritesAndAcknowledgesBeforeRelease() = exercise(0)
    @Test fun missingProtectionCapabilityRefusesEveryWrite() = exercise(1)
    @Test fun cancelledSavedOperationReconcilesBeforeReleasingHeldOwners() = exercise(2)
    @Test fun incompleteSavedRecoveryNeverReleasesOwners() = exercise(3)
    @Test fun pendingActualDiskDrainRefusesReconciliation() = exercise(4)
    @Test fun stoppingReconciliationKeepsOwnersAfterWorkerDrain() = exercise(5)

    @Test fun refusedFullImageRegistrationPreventsIntentAndWrites() = exercise(6)
    @Test fun refusedDurableAcknowledgmentKeepsRuntimeAndPersistentProtection() = exercise(7)
    @Test fun stoppingStartedProtectionWaitsForItsActualDatabaseResult() = exercise(8)
    @Test fun stoppingStartedAcknowledgmentKeepsOwnersDespiteLateDecision() = exercise(9)

    @Test fun shutdownFromReconciliationResourceClosureCannotReleaseOwners() = exercise(10)

    private fun exercise(mode: Int) {
        val path=Files.createTempDirectory("guardian-protected-host").resolve("audit.sqlite")
        val db=SqliteStorageBackend(path)
        db.open()
        val a=ItemSlotAddress(ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"),BlockPosition(1,64,1)),0)
        val b=ItemSlotAddress(ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"),BlockPosition(2,64,1)),0)
        val spare=ItemSlotAddress(a.owner,1)
        val item=ItemStackSnapshot(ResourceId.parse("minecraft:coal"),2,BinaryPayload.of(byteArrayOf(1,2)))
        val row=ContainerTransactionSnapshot(UUID.randomUUID(),100,ActorIdentity.System("hopper"),0,ContainerAction.HOPPER_TRANSFER,
            listOf(ItemSlotChange(a,item,ItemStackSnapshot.EMPTY),ItemSlotChange(b,ItemStackSnapshot.EMPTY,item)))
        db.append(listOf(ContainerAuditEntry(row)))
        val record=db.prepareItemRollback(UUID.randomUUID(),200,listOf(row))
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val journal: ItemRollbackJournal=when(mode){
            1 -> object: ItemRollbackJournal by db {}
            6,7,8,9 -> object: ItemRollbackProtectionJournal by db {
                override fun protectItemRollback(record:ItemRollbackRecord,original:InventorySnapshot):Boolean {
                    if(mode==6)return false
                    if(mode==8){entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
                    return db.protectItemRollback(record,original)
                }
                override fun acknowledgeItemRollback(record:ItemRollbackRecord,images:ItemRollbackImages,saved:InventorySnapshot):Boolean {
                    if(mode==7)return false
                    if(mode==9){entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
                    return db.acknowledgeItemRollback(record,images,saved)
                }
            }
            else -> db
        }
        val coordination=ItemOwnerCoordination()
        val scope=ItemOperationScope(journal){db.close()}
        val live=linkedMapOf(a to ItemStackSnapshot.EMPTY,b to item,spare to ItemStackSnapshot.EMPTY)
        var writes=0
        val pending=CompletableFuture<InventorySnapshot?>()
        val port=object: ItemOperationPort {
            override fun isExclusiveAndCurrent(owners:Set<ItemSlotOwner>)=true
            override fun readOwners(owners:Set<ItemSlotOwner>)=InventorySnapshot(live)
            override fun writeSlot(address:ItemSlotAddress,expected:ItemStackSnapshot,replacement:ItemStackSnapshot){
                assertTrue(db.itemRollbackProtected(record.operationId))
                assertNotNull(db.itemRollbackImages(record.operationId))
                assertEquals(expected,live[address]);live[address]=replacement;writes++
            }
            override fun saveAndReadBack(owner:ItemSlotOwner,addresses:Set<ItemSlotAddress>):CompletionStage<InventorySnapshot?> =
                if(mode in 2..5 || mode==10)pending else CompletableFuture.completedFuture(InventorySnapshot(live.filterKeys{it.owner==owner}))
            override fun close()=Unit
        }
        val host=assertNotNull(scope.startProtected(record,coordination,{port}))
        fun until(condition:()->Boolean){
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(!condition()){check(System.nanoTime()<deadline){"Protected host timed out: ${host.state}"};scope.advance();Thread.sleep(1)}
        }
        try {
            if(mode==8 || mode==9){
                until{entered.count==0L}
                scope.cancel(host);assertFalse(host.isJournalDrained);assertTrue(coordination.hasJournalRetention())
                release.countDown();until{host.state==ItemOperationState.RECOVERY_REQUIRED}
                assertTrue(host.isJournalDrained);assertTrue(coordination.hasJournalRetention())
                assertEquals(if(mode==8)0 else 2,writes)
                assertEquals(mode==9,db.itemRollbackImages(record.operationId)!!.acknowledged)
            } else if(mode in 2..5 || mode==10){
                until{host.state==ItemOperationState.SAVING};scope.cancel(host)
                until{host.state==ItemOperationState.RECOVERY_REQUIRED}
                assertTrue(host.isJournalDrained);assertTrue(coordination.hasJournalRetention())
                assertEquals(2,writes)
                pending.complete(InventorySnapshot(live))
                assertTrue(db.transitionItemRollback(record.operationId,ItemRollbackPhase.APPLYING,ItemRollbackPhase.RECOVERY_REQUIRED))
                val recovery=checkNotNull(db.itemRollback(record.operationId))
                val readOnly=object: ItemReconciliationPort, AutoCloseable {
                    override fun close(){if(mode==10)scope.close()}
                    override fun isExclusiveAndQuiescent(owners:Set<ItemSlotOwner>)=true
                    override fun readLiveOwners(owners:Set<ItemSlotOwner>)=InventorySnapshot(live)
                    override fun readSavedOwner(owner:ItemSlotOwner):CompletionStage<InventorySnapshot?> =
                        CompletableFuture.completedFuture(InventorySnapshot(live.filterKeys{it.owner==owner && (mode!=3 || it!=spare)}))
                }
                val disk=CompletableFuture<Void>()
                if(mode==4){
                    assertFailsWith<IllegalStateException>{scope.reconcile(host,recovery,readOnly,disk.minimalCompletionStage())}
                    assertTrue(coordination.hasJournalRetention());assertEquals(ItemOperationState.RECOVERY_REQUIRED,host.state)
                }
                disk.complete(null)
                scope.reconcile(host,recovery,readOnly,disk.minimalCompletionStage())
                if(mode==5)scope.cancel(host)
                until{host.state in setOf(ItemOperationState.COMPLETED,ItemOperationState.RECOVERY_REQUIRED)}
                if(mode==3 || mode==5 || mode==10){assertTrue(coordination.hasJournalRetention());assertEquals(mode==10,db.itemRollbackImages(record.operationId)!!.acknowledged)}
                else {assertFalse(coordination.hasJournalRetention());assertTrue(db.itemRollbackImages(record.operationId)!!.acknowledged)}
            } else {
                until{host.state in setOf(ItemOperationState.COMPLETED,ItemOperationState.RECOVERY_REQUIRED)}
                if(mode==0){assertEquals(2,writes);assertFalse(coordination.hasJournalRetention());assertTrue(db.itemRollbackImages(record.operationId)!!.acknowledged)}
                else {
                    assertEquals(if(mode==7)2 else 0,writes);assertTrue(coordination.hasJournalRetention())
                    assertEquals(if(mode==7)ItemRollbackPhase.COMPLETED else ItemRollbackPhase.PREPARED,db.itemRollback(record.operationId)!!.phase)
                    if(mode==7){assertTrue(db.itemRollbackProtected(record.operationId));assertFalse(db.itemRollbackImages(record.operationId)!!.acknowledged)}
                }
            }
        } finally {
            release.countDown();scope.close();until{scope.state in setOf(ItemOperationScopeState.CLOSED,ItemOperationScopeState.FAILED)}
        }
        assertEquals(ItemOperationScopeState.CLOSED,scope.state)
        SqliteStorageBackend(path).use { reopened ->
            reopened.open()
            val receipt=reopened.itemRollbackImages(record.operationId)
            if(mode==1 || mode==6)assertNull(receipt)
            else {
                assertNotNull(receipt)
                assertEquals(mode in setOf(0,2,4,9,10),receipt.acknowledged)
                assertEquals(!receipt.acknowledged,reopened.itemRollbackProtected(record.operationId))
            }
        }
    }
}
