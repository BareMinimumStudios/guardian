package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.jdbc.SqliteStorageBackend
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Actual journal worker/SQLite decisions; live and saved images are controlled synthetic ports. */
class ItemReconciliationDriverTest {
    private val a=ItemSlotAddress(ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"),BlockPosition(0,64,0)),0)
    private val b=ItemSlotAddress(ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"),BlockPosition(1,64,0)),0)
    private val item=ItemStackSnapshot(ResourceId.parse("minecraft:coal"),2,BinaryPayload.of(byteArrayOf(1)))
    private val spare=ItemStackSnapshot(ResourceId.parse("minecraft:diamond_pickaxe"),1,BinaryPayload.of(byteArrayOf(17)))
    private inner class Fixture(phase: ItemRollbackPhase=ItemRollbackPhase.COMPLETED,withImages: Boolean=true) : AutoCloseable {
        val main=Thread.currentThread()
        val db=SqliteStorageBackend(Files.createTempDirectory("guardian-reconcile-driver").resolve("audit.sqlite"))
        val record: ItemRollbackRecord
        val images: ItemRollbackImages
        var now=0L
        var quiescent=true
        var liveReads=0
        var savedReads=0
        val ackCalls=AtomicInteger()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        @Volatile var blockAck=false
        @Volatile var failAfterAck=false
        @Volatile var refuseAck=false
        var pending: CompletableFuture<InventorySnapshot?>?=null
        var onLive: () -> Unit = {}
        var onSaved: () -> Unit = {}
        var transform: (InventorySnapshot) -> InventorySnapshot? = {it}
        val live: MutableMap<ItemSlotAddress,ItemStackSnapshot>
        val worker: ItemRollbackJournalWorker
        val port: ItemReconciliationPort
        var driver: ItemReconciliationDriver
        init {
            db.open()
            val row=ContainerTransactionSnapshot(UUID.randomUUID(),100,ActorIdentity.System("hopper"),0,ContainerAction.HOPPER_TRANSFER,listOf(ItemSlotChange(a,item,ItemStackSnapshot.EMPTY),ItemSlotChange(b,ItemStackSnapshot.EMPTY,item)))
            db.append(listOf(ContainerAuditEntry(row)));val prepared=db.prepareItemRollback(UUID.randomUUID(),200,listOf(row))
            val original=InventorySnapshot(mapOf(a to ItemStackSnapshot.EMPTY,a.copy(index=1) to spare,a.copy(index=2) to ItemStackSnapshot.EMPTY,b to item,b.copy(index=1) to ItemStackSnapshot.EMPTY,b.copy(index=2) to spare))
            images=ItemRollbackImages(prepared,original)
            if(withImages)db.protectItemRollback(prepared,original) else db.protectItemRollback(prepared)
            db.transitionItemRollback(prepared.operationId,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING)
            db.transitionItemRollback(prepared.operationId,ItemRollbackPhase.APPLYING,phase);record=checkNotNull(db.itemRollback(prepared.operationId))
            live=images.expected.slots.toMutableMap()
            val journal=object : ItemRollbackProtectionJournal by db {
                override fun acknowledgeItemRollback(record: ItemRollbackRecord,images: ItemRollbackImages,saved: InventorySnapshot): Boolean {
                    check(Thread.currentThread()!==main);ackCalls.incrementAndGet()
                    if(blockAck) {entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
                    if(refuseAck)return false
                    val result=db.acknowledgeItemRollback(record,images,saved)
                    if(failAfterAck)error("Decision committed but reply failed")
                    return result
                }
            }
            worker=ItemRollbackJournalWorker(journal)
            port=object : ItemReconciliationPort {
                override fun isExclusiveAndQuiescent(owners: Set<ItemSlotOwner>): Boolean {check(Thread.currentThread()===main);return quiescent}
                override fun readLiveOwners(owners: Set<ItemSlotOwner>): InventorySnapshot {check(Thread.currentThread()===main);liveReads++;onLive();return InventorySnapshot(live)}
                override fun readSavedOwner(owner: ItemSlotOwner): CompletionStage<InventorySnapshot?> {
                    check(Thread.currentThread()===main);savedReads++;onSaved()
                    return pending ?: CompletableFuture.completedFuture(transform(InventorySnapshot(images.expected.slots.filterKeys {it.owner==owner})))
                }
            }
            driver=ItemReconciliationDriver(record,worker,port,{now})
        }
        fun until(condition: () -> Boolean) {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(!condition()) {check(System.nanoTime()<deadline) {"Reconciliation timed out in test"};driver.advance();Thread.sleep(1)}
        }
        fun finish()=until {driver.state in setOf(ItemReconciliationState.RESOLVED,ItemReconciliationState.ALREADY_RESOLVED,ItemReconciliationState.UNRESOLVED)}
        fun held() {assertEquals(ItemReconciliationState.UNRESOLVED,driver.state);assertTrue(db.itemRollbackProtected(record.operationId));assertFalse(assertNotNull(db.itemRollbackImages(record.operationId)).acknowledged);assertEquals(0,ackCalls.get())}
        override fun close() {driver.stop();release.countDown();worker.close();worker.drained.toCompletableFuture().get(5,TimeUnit.SECONDS);db.close()}
    }
    @Test fun completedRecordUsesEverySavedSlotAndConfirmsDurableRelease() = Fixture().use {f->
        f.finish();assertEquals(ItemReconciliationState.RESOLVED,f.driver.state);assertEquals(2,f.savedReads);assertEquals(1,f.ackCalls.get())
        assertFalse(f.db.itemRollbackProtected(f.record.operationId));assertEquals(0L,f.db.health().unfinishedItemRollbacks)
        assertEquals(f.images.expected.slots,f.live);f.driver.advance();assertEquals(1,f.ackCalls.get())
    }
    @Test fun recoveryRecordCompletesOnlyFromExactSavedAndLiveExpectedImages() = Fixture(ItemRollbackPhase.RECOVERY_REQUIRED).use {f->
        f.finish();assertEquals(ItemReconciliationState.RESOLVED,f.driver.state);assertEquals(ItemRollbackPhase.COMPLETED,f.db.itemRollback(f.record.operationId)?.phase)
    }
    @Test fun missingTrustedQuiescenceRefusesBeforeSavedReads() = Fixture().use {f->f.quiescent=false;f.finish();f.held();assertEquals(0,f.savedReads)}
    @Test fun changedUnloggedLiveComponentsRefuseBeforeSavedReads() = Fixture().use {f->f.live[a.copy(index=1)]=spare.copy(itemData=BinaryPayload.of(byteArrayOf(18)));f.finish();f.held();assertEquals(0,f.savedReads)}
    @Test fun partialLiveStateCannotReleaseEvenIfSavedStateMatches() = Fixture().use {f->f.live[b]=item;f.finish();f.held();assertEquals(0,f.savedReads)}
    @Test fun changedSavedTransferSlotRefusesAcknowledgment() = Fixture().use {f->f.transform={InventorySnapshot(it.slots.toMutableMap().also {m->m[m.keys.first {a->a.index==0}]=item})};f.finish();f.held()}
    @Test fun omittedEmptySavedSlotRefusesCompleteComparison() = Fixture().use {f->f.transform={InventorySnapshot(it.slots.filterKeys {a->a.index!=2})};f.finish();f.held()}
    @Test fun changedUnloggedSavedComponentsRefuseAcknowledgment() = Fixture().use {f->f.transform={InventorySnapshot(it.slots.toMutableMap().also {m->m[m.keys.first {a->a.index==1}]=spare.copy(itemData=BinaryPayload.of(byteArrayOf(18)))})};f.finish();f.held()}
    @Test fun extraSavedOwnerCannotMasqueradeAsAFullOwnerImage() = Fixture().use {f->f.transform={InventorySnapshot(it.slots+(ItemSlotAddress(ItemSlotOwner.PlayerInventory(UUID.randomUUID()),0) to item))};f.finish();f.held()}
    @Test fun missingSavedFileLeavesProtectionHeld() = Fixture().use {f->f.transform={null};f.finish();f.held()}
    @Test fun changedJournalPhaseDuringSavedReadsRefusesFinalAcknowledgment() = Fixture(ItemRollbackPhase.RECOVERY_REQUIRED).use {f->
        f.onSaved={f.db.transitionItemRollback(f.record.operationId,ItemRollbackPhase.RECOVERY_REQUIRED,ItemRollbackPhase.COMPLETED)};f.finish();f.held()
    }
    @Test fun markerOnlyLegacyRecordCannotBeResolvedBySlotObservations() = Fixture(withImages=false).use {f->f.finish();assertEquals(ItemReconciliationState.UNRESOLVED,f.driver.state);assertEquals(0,f.savedReads);assertEquals(0,f.ackCalls.get());assertTrue(f.db.itemRollbackProtected(f.record.operationId))}
    @Test fun stopBeforePollingSubmitsNoSavedReadOrAcknowledgment() = Fixture().use {f->f.driver.stop();f.finish();f.held();assertEquals(0,f.liveReads);assertEquals(0,f.savedReads)}
    @Test fun stopDuringSavedReadDoesNotCancelIoOrAcknowledgeLateResults() = Fixture().use {f->
        f.pending=CompletableFuture();f.until {f.savedReads==1};f.driver.stop();assertFalse(f.pending!!.isDone)
        f.pending!!.complete(InventorySnapshot(f.images.expected.slots.filterKeys {it.owner==a.owner}));f.driver.advance();f.held()
    }
    @Test fun stoppedStartedAcknowledgmentKeepsItsRealLateDurableOutcome() = Fixture().use {f->
        f.blockAck=true;f.until {f.entered.count==0L};f.driver.stop();assertTrue(f.driver.acknowledgmentAttempt!!.toCompletableFuture().cancel(false))
        f.worker.close();assertFalse(f.worker.drained.toCompletableFuture().isDone);f.release.countDown();f.worker.drained.toCompletableFuture().get(5,TimeUnit.SECONDS)
        assertTrue(f.driver.acknowledgmentAttempt!!.toCompletableFuture().get(5,TimeUnit.SECONDS));assertEquals(ItemReconciliationState.UNRESOLVED,f.driver.advance())
        assertTrue(assertNotNull(f.db.itemRollbackImages(f.record.operationId)).acknowledged);assertFalse(f.db.itemRollbackProtected(f.record.operationId));assertEquals(1,f.ackCalls.get())
    }
    @Test fun timeoutDuringAcknowledgmentDoesNotCancelOrRetryDecision() = Fixture().use {f->
        f.blockAck=true;f.until {f.entered.count==0L};f.now=TimeUnit.SECONDS.toNanos(11);f.driver.advance();assertEquals(ItemReconciliationState.UNRESOLVED,f.driver.state)
        f.worker.close();f.release.countDown();f.worker.drained.toCompletableFuture().get(5,TimeUnit.SECONDS)
        assertTrue(assertNotNull(f.db.itemRollbackImages(f.record.operationId)).acknowledged);assertEquals(1,f.ackCalls.get())
    }
    @Test fun refusedDatabaseAcknowledgmentLeavesReceiptAndClaimsHeld() = Fixture().use {f->
        f.refuseAck=true;f.finish();assertEquals(ItemReconciliationState.UNRESOLVED,f.driver.state);assertTrue(f.db.itemRollbackProtected(f.record.operationId));assertFalse(assertNotNull(f.db.itemRollbackImages(f.record.operationId)).acknowledged);assertEquals(1,f.ackCalls.get())
    }
    @Test fun committedDecisionWithFailedReplyCanBeConfirmedWithoutReadingOrWritingItemsAgain() = Fixture(ItemRollbackPhase.RECOVERY_REQUIRED).use {f->
        f.failAfterAck=true;f.finish();assertEquals(ItemReconciliationState.UNRESOLVED,f.driver.state);assertTrue(assertNotNull(f.db.itemRollbackImages(f.record.operationId)).acknowledged)
        val oldReads=f.savedReads;f.quiescent=false;f.driver=ItemReconciliationDriver(assertNotNull(f.db.itemRollback(f.record.operationId)),f.worker,f.port,{f.now});f.finish()
        assertEquals(ItemReconciliationState.ALREADY_RESOLVED,f.driver.state);assertEquals(oldReads,f.savedReads);assertEquals(1,f.ackCalls.get())
    }
    @Test fun repeatedFreshRecoveryUsesDurableDecisionWithoutNewOwnerReads() = Fixture().use {f->
        f.finish();val oldReads=f.liveReads;f.quiescent=false;f.live.clear();f.driver=ItemReconciliationDriver(assertNotNull(f.db.itemRollback(f.record.operationId)),f.worker,f.port,{f.now});f.finish()
        assertEquals(ItemReconciliationState.ALREADY_RESOLVED,f.driver.state);assertEquals(oldReads,f.liveReads);assertEquals(1,f.ackCalls.get())
    }
    @Test fun stopFromLiveReadCallbackCannotBeOverwrittenByProgress() = Fixture().use {f->f.onLive={f.driver.stop()};f.finish();f.held();assertEquals(0,f.savedReads)}
    @Test fun stopFromSavedReadCallbackCannotAdvanceToAcknowledgment() = Fixture().use {f->f.onSaved={f.driver.stop()};f.finish();f.held();assertEquals(1,f.savedReads)}
    @Test fun nestedPollingIsRefusedWithoutDuplicatingRequests() = Fixture().use {f->
        f.onLive={assertFailsWith<IllegalStateException>{f.driver.advance()}};f.finish();assertEquals(ItemReconciliationState.RESOLVED,f.driver.state);assertEquals(2,f.savedReads);assertEquals(1,f.ackCalls.get())
    }
    @Test fun controlsRejectOtherThreads() = Fixture().use {f->
        val pool=Executors.newSingleThreadExecutor();try {pool.submit {assertFailsWith<IllegalStateException>{f.driver.advance()};assertFailsWith<IllegalStateException>{f.driver.stop()};assertFailsWith<IllegalStateException>{f.driver.acknowledgmentAttempt}}.get(5,TimeUnit.SECONDS)}finally{pool.shutdownNow()}
        f.finish();assertEquals(ItemReconciliationState.RESOLVED,f.driver.state)
    }
    @Test fun stopFromJournalReceiptCallbackCannotReviveDriver() = Fixture().use {f->
        val journal=object : ItemReconciliationJournalPort by f.worker {
            override fun readImages(operationId: UUID): CompletionStage<ItemRollbackImageRecord?> {f.driver.stop();return f.worker.readImages(operationId)}
        }
        f.driver=ItemReconciliationDriver(f.record,journal,f.port,{f.now});f.finish();f.held();assertEquals(0,f.savedReads)
    }
    @Test fun stopFromAcknowledgmentCallbackPreservesSubmittedOutcomeWithoutRevival() = Fixture().use {f->
        val journal=object : ItemReconciliationJournalPort by f.worker {
            override fun acknowledge(record: ItemRollbackRecord,images: ItemRollbackImages,saved: InventorySnapshot): CompletionStage<Boolean> {
                f.driver.stop();return f.worker.acknowledge(record,images,saved)
            }
        }
        f.driver=ItemReconciliationDriver(f.record,journal,f.port,{f.now});f.finish()
        assertEquals(ItemReconciliationState.UNRESOLVED,f.driver.state)
        assertTrue(f.driver.acknowledgmentAttempt!!.toCompletableFuture().get(5,TimeUnit.SECONDS))
        assertTrue(assertNotNull(f.db.itemRollbackImages(f.record.operationId)).acknowledged);assertEquals(1,f.ackCalls.get())
        assertEquals(ItemReconciliationState.UNRESOLVED,f.driver.advance())
    }
    @Test fun timeoutDuringSavedReadKeepsFutureAndProtectionIntact() = Fixture().use {f->
        f.pending=CompletableFuture();f.until {f.savedReads==1};f.now=TimeUnit.SECONDS.toNanos(11);f.driver.advance();f.held();assertFalse(f.pending!!.isDone)
    }
}
