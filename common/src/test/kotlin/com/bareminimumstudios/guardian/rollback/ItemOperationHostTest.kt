package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import java.util.concurrent.*
import kotlin.test.*

class ItemOperationHostTest {
    private val a = ItemSlotAddress(ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"), BlockPosition(1,64,1)),0)
    private val b = ItemSlotAddress(ItemSlotOwner.PlayerInventory(UUID.randomUUID()),0)
    private val spareA = ItemSlotAddress(a.owner,1)
    private val spareB = ItemSlotAddress(b.owner,1)
    private val item = ItemStackSnapshot(ResourceId.parse("minecraft:coal"),2,BinaryPayload.of(byteArrayOf(1)))
    private fun record() = ItemRollbackRecord(UUID.randomUUID(),1,ItemRollbackPhase.PREPARED,listOf(
        ItemRollbackEntry(UUID.randomUUID(),listOf(ItemSlotChange(a,item,ItemStackSnapshot.EMPTY),ItemSlotChange(b,ItemStackSnapshot.EMPTY,item)))))
    private fun phase(record: ItemRollbackRecord, phase: ItemRollbackPhase) = ItemRollbackRecord(record.operationId,record.createdAt,phase,record.entries)
    private var now = 0L
    private val coordination = ItemOwnerCoordination { now }
    private lateinit var host: ItemOperationHost
    private inner class Journal : ItemRollbackJournal {
        @Volatile var value = record()
        @Volatile var reads = 0
        @Volatile var marks = 0
        @Volatile var commits = 0
        var blockPhase: ItemRollbackPhase? = null
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var failAcknowledgment = false
        override fun itemRollback(operationId: UUID): ItemRollbackRecord { reads++; return value }
        override fun transitionItemRollback(operationId: UUID, expected: ItemRollbackPhase, next: ItemRollbackPhase): Boolean {
            if (value.phase != expected) return false
            if (blockPhase == next) { entered.countDown(); check(release.await(5,TimeUnit.SECONDS)) }
            value = phase(value,next)
            if (next == ItemRollbackPhase.APPLYING) marks++ else commits++
            if (next == ItemRollbackPhase.COMPLETED && failAcknowledgment) error("Committed but acknowledgment failed")
            return true
        }
        override fun prepareItemRollback(operationId: UUID, createdAt: Long, newestFirst: List<ContainerTransactionSnapshot>): ItemRollbackRecord = error("Unexpected prepare")
        override fun unfinishedItemRollbacks(limit: Int): List<ItemRollbackSummary> = error("Unexpected list")
    }
    private inner class Port : ItemOperationPort {
        val live = linkedMapOf(a to ItemStackSnapshot.EMPTY,b to item,spareA to ItemStackSnapshot.EMPTY,spareB to ItemStackSnapshot.EMPTY)
        var exclusive = true
        var closed = false
        var writes = 0
        var saves = 0
        var onWrite: () -> Unit = {}
        var onClose: () -> Unit = {}
        var onSaved: (InventorySnapshot) -> InventorySnapshot = { it }
        var pending: CompletableFuture<InventorySnapshot?>? = null
        override fun isExclusiveAndCurrent(owners: Set<ItemSlotOwner>) = exclusive && !closed
        override fun readOwners(owners: Set<ItemSlotOwner>) = InventorySnapshot(live)
        override fun writeSlot(address: ItemSlotAddress,expected: ItemStackSnapshot,replacement: ItemStackSnapshot) {
            check(live[address] == expected);live[address] = replacement;writes++;onWrite()
        }
        override fun saveAndReadBack(owner: ItemSlotOwner,addresses: Set<ItemSlotAddress>): CompletionStage<InventorySnapshot?> {
            saves++
            return pending ?: CompletableFuture.completedFuture(onSaved(InventorySnapshot(live.filterKeys { it.owner == owner })))
        }
        override fun close() { closed = true; onClose() }
    }
    private fun start(journal: Journal,port: Port): ItemOperationHost {
        host = assertNotNull(ItemOperationHost.start(journal.value,coordination,journal,{ port },{ now }))
        return host
    }
    private fun until(test: () -> Boolean) {
        val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while (!test()) { check(System.nanoTime()<deadline) { "Host test timed out" };host.advance();Thread.sleep(1) }
    }
    private fun finish() = until { host.state in setOf(ItemOperationState.COMPLETED,ItemOperationState.RECOVERY_REQUIRED) }
    private fun held(journal: Journal) {
        assertEquals(ItemOperationState.RECOVERY_REQUIRED,host.state)
        assertTrue(host.isJournalDrained)
        assertTrue(coordination.hasJournalRetention())
        assertNull(coordination.acquire(UUID.randomUUID(),listOf(a.owner)))
        assertNull(coordination.acquire(UUID.randomUUID(),listOf(b.owner)))
        host.close();host.advance()
        assertTrue(coordination.hasJournalRetention())
        assertNotNull(journal.value)
    }

    @Test fun successfulHostAppliesSavesConfirmsAndReleasesAllOwners() {
        val journal=Journal();val port=Port();start(journal,port);finish()
        assertEquals(ItemOperationState.COMPLETED,host.state);assertEquals(ItemRollbackPhase.COMPLETED,journal.value.phase)
        assertEquals(2,port.writes);assertEquals(2,port.saves);assertEquals(1,journal.marks);assertEquals(1,journal.commits)
        assertEquals(item,port.live[a]);assertEquals(ItemStackSnapshot.EMPTY,port.live[b]);assertTrue(port.closed)
        assertFalse(coordination.hasJournalRetention());assertNotNull(coordination.acquire(UUID.randomUUID(),listOf(a.owner,b.owner)))
        host.advance();host.close();assertEquals(2,port.writes)
    }
    @Test fun missingTrustedExclusionRefusesBeforeJournalRequests() {
        val journal=Journal();val port=Port().apply { exclusive=false }
        assertFailsWith<IllegalStateException> { start(journal,port) }
        assertTrue(port.closed);assertFalse(coordination.hasReservations());assertEquals(0,journal.reads);assertEquals(0,journal.marks)
    }
    @Test fun unavailableOriginalContentsRefuseWithoutWritesOrIntent() {
        val journal=Journal();val port=Port().apply { live[b]=ItemStackSnapshot.EMPTY };start(journal,port);finish();held(journal)
        assertEquals(0,port.writes);assertEquals(0,journal.marks);assertEquals(ItemRollbackPhase.PREPARED,journal.value.phase)
    }
    @Test fun unchangedSlotMutationDuringIntentReadRefusesBeforeAnySetter() {
        val journal=Journal();val port=Port();start(journal,port);host.advance();port.live[spareA]=item;finish();held(journal)
        assertEquals(0,port.writes);assertEquals(0,journal.marks)
    }
    @Test fun stopDuringStartedIntentPreservesLateApplyingAndHeldOwners() {
        val journal=Journal().apply { blockPhase=ItemRollbackPhase.APPLYING };val port=Port();start(journal,port)
        try {
            until { journal.entered.count==0L };host.close();assertTrue(coordination.hasJournalRetention());assertFalse(host.isJournalDrained)
        } finally { journal.release.countDown() }
        finish();held(journal);assertEquals(ItemRollbackPhase.APPLYING,journal.value.phase);assertEquals(0,port.writes)
    }
    @Test fun partialSetterFailureNeverRetriesOrUndoesChanges() {
        val journal=Journal();val port=Port().apply { onWrite={error("Failure after setter")} };start(journal,port);finish();held(journal)
        assertEquals(1,port.writes);assertEquals(0,port.saves);assertEquals(item,port.live[a]);assertEquals(item,port.live[b]);assertEquals(ItemRollbackPhase.APPLYING,journal.value.phase)
    }
    @Test fun changedUnloggedSavedSlotRefusesCompletion() {
        val journal=Journal();val port=Port().apply { onSaved={ InventorySnapshot(it.slots.toMutableMap().also { map->map[spareA]=item }) } }
        start(journal,port);finish();held(journal);assertEquals(0,journal.commits);assertEquals(ItemRollbackPhase.APPLYING,journal.value.phase)
    }
    @Test fun savedImageMissingAnUnloggedSlotRefusesCompletion() {
        val journal=Journal();val port=Port().apply { onSaved={ InventorySnapshot(it.slots.filterKeys { key->key!=spareA }) } }
        start(journal,port);finish();held(journal);assertEquals(0,journal.commits)
    }
    @Test fun savedImageContainingAnotherOwnerRefusesCompletion() {
        val journal=Journal();val port=Port().apply { onSaved={ InventorySnapshot(it.slots.toMutableMap().also { map->map[spareB]=ItemStackSnapshot.EMPTY;map[spareA]=ItemStackSnapshot.EMPTY }) } }
        start(journal,port);finish();held(journal);assertEquals(0,journal.commits)
    }
    @Test fun cancellingExposedCompletionCannotMaskOrCancelTheRealCommit() {
        val journal=Journal().apply { blockPhase=ItemRollbackPhase.COMPLETED };val port=Port();start(journal,port)
        try { until { journal.entered.count==0L };assertNotNull(host.completionAttempt).toCompletableFuture().cancel(false);assertTrue(coordination.hasJournalRetention()) }
        finally { journal.release.countDown() }
        finish();assertEquals(ItemOperationState.COMPLETED,host.state);assertEquals(1,journal.commits);assertFalse(coordination.hasJournalRetention())
    }
    @Test fun stopDuringCommitRetainsProtectionAfterItsLateCompletedOutcome() {
        val journal=Journal().apply { blockPhase=ItemRollbackPhase.COMPLETED };val port=Port();start(journal,port)
        try {
            until { journal.entered.count==0L };host.close();assertTrue(port.closed);assertTrue(coordination.hasJournalRetention());assertFalse(host.isJournalDrained)
        } finally { journal.release.countDown() }
        finish();held(journal);assertEquals(ItemRollbackPhase.COMPLETED,journal.value.phase);assertEquals(1,journal.commits)
    }
    @Test fun failedAcknowledgmentAfterCommitNeverReleasesProtection() {
        val journal=Journal().apply { failAcknowledgment=true };val port=Port();start(journal,port);finish();held(journal)
        assertEquals(ItemRollbackPhase.COMPLETED,journal.value.phase);assertEquals(1,journal.commits)
    }
    @Test fun leaseExpiryDuringSaveDoesNotCancelPendingFileReadback() {
        val journal=Journal();val port=Port().apply { pending=CompletableFuture() };start(journal,port);until { port.saves==1 }
        now=TimeUnit.SECONDS.toNanos(11);finish();held(journal);assertFalse(port.pending!!.isCancelled);assertEquals(0,journal.commits)
        port.pending!!.complete(InventorySnapshot(port.live.filterKeys { it.owner==a.owner }));assertEquals(ItemOperationState.RECOVERY_REQUIRED,host.advance())
    }
    @Test fun changedContentsAfterConfirmationRequireReconciliationInsteadOfRelease() {
        val journal=Journal();val port=Port();start(journal,port);until { host.state==ItemOperationState.DRAINING }
        port.live[spareA]=item;finish();held(journal);assertEquals(ItemRollbackPhase.COMPLETED,journal.value.phase)
    }
    @Test fun closureFailureAfterCompletionRetainsOwners() {
        val journal=Journal();val port=Port().apply { onClose={error("Closure failed")} };start(journal,port);finish();held(journal)
        assertEquals(ItemRollbackPhase.COMPLETED,journal.value.phase)
    }
    @Test fun closureCallbackCannotReviveOrPrematurelyReleaseOwners() {
        val journal=Journal();val port=Port().apply { onClose={coordination.invalidateAll();assertNull(coordination.acquire(UUID.randomUUID(),listOf(a.owner)))} }
        start(journal,port);finish();held(journal);assertEquals(ItemRollbackPhase.COMPLETED,journal.value.phase)
    }
    @Test fun stopInsideSetterPreventsSaveHandoffAndFurtherWrites() {
        val journal=Journal();val port=Port().apply { onWrite={host.close()} };start(journal,port);finish();held(journal)
        assertEquals(1,port.writes);assertEquals(0,port.saves);assertEquals(ItemRollbackPhase.APPLYING,journal.value.phase)
    }
    @Test fun reentrantPollingCannotRepeatASetter() {
        val journal=Journal();val port=Port().apply { onWrite={host.advance()} };start(journal,port);finish();held(journal)
        assertEquals(1,port.writes);assertEquals(0,port.saves)
    }
    @Test fun offThreadControlIsRefusedWithoutChangingTheOperation() {
        val journal=Journal();val port=Port();start(journal,port)
        CompletableFuture.runAsync { assertFailsWith<IllegalStateException> { host.advance() };assertFailsWith<IllegalStateException> { host.close() } }.get(5,TimeUnit.SECONDS)
        assertEquals(ItemOperationState.APPLYING,host.state);finish();assertEquals(ItemOperationState.COMPLETED,host.state)
    }
    @Test fun changedFreshJournalRefusesIntentAndWrites() {
        val journal=Journal();val port=Port();start(journal,port);journal.value=record();finish();held(journal)
        assertEquals(0,port.writes);assertEquals(0,journal.marks)
    }
    @Test fun duplicateOperationNeverCallsAnotherBinder() {
        val journal=Journal();val port=Port();start(journal,port)
        assertNull(ItemOperationHost.start(journal.value,coordination,journal,{ error("Duplicate binder") },{now}))
        finish();assertEquals(ItemOperationState.COMPLETED,host.state)
    }
    @Test fun nonPreparedRecordIsRejectedBeforeAcquiringOwners() {
        val journal=Journal();assertFailsWith<IllegalArgumentException> {
            ItemOperationHost.start(phase(journal.value,ItemRollbackPhase.APPLYING),coordination,journal,{error("Unexpected binder")},{now})
        };assertFalse(coordination.hasReservations());assertEquals(0,journal.reads)
    }
    @Test fun identityLossDuringSaveLeavesItsLateResultUnacknowledged() {
        val journal=Journal();val port=Port().apply { pending=CompletableFuture() };start(journal,port);until { port.saves==1 }
        port.exclusive=false;finish();held(journal);assertFalse(port.pending!!.isCancelled);assertEquals(0,journal.commits)
    }
    @Test fun explicitRetentionKeepsRevokedOwnersAfterDrainUntilReconciliation() {
        val journal=Journal();val worker=ItemRollbackJournalWorker(journal);val lease=assertNotNull(coordination.acquire(UUID.randomUUID(),listOf(a.owner)))
        val retention=lease.retainUntilJournalReconciled(worker)
        assertFailsWith<IllegalStateException> { retention.releaseAfterReconciliation() };lease.close();worker.close()
        assertTrue(retention.isDrained);assertTrue(coordination.hasJournalRetention());assertNull(coordination.acquire(UUID.randomUUID(),listOf(a.owner)))
        CompletableFuture.runAsync { assertFailsWith<IllegalStateException> { retention.releaseAfterReconciliation() } }.get(5,TimeUnit.SECONDS)
        retention.releaseAfterReconciliation();assertFalse(coordination.hasJournalRetention());assertEquals(ItemOwnerLeaseState.RELEASED,lease.state)
        assertNotNull(coordination.acquire(UUID.randomUUID(),listOf(a.owner)))
    }
}
