package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import java.util.concurrent.*
import kotlin.test.*

class ItemApplyDriverTest {
    private val a = ItemSlotAddress(ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"), BlockPosition(1,64,1)),0)
    private val b = ItemSlotAddress(ItemSlotOwner.PlayerInventory(UUID.randomUUID()),0)
    private val spare = ItemSlotAddress(a.owner,1)
    private val item = ItemStackSnapshot(ResourceId.parse("minecraft:coal"),2,BinaryPayload.of(byteArrayOf(1)))
    private fun record(phase: ItemRollbackPhase = ItemRollbackPhase.PREPARED) = ItemRollbackRecord(UUID.randomUUID(),1,phase,listOf(
        ItemRollbackEntry(UUID.randomUUID(),listOf(ItemSlotChange(a,item,ItemStackSnapshot.EMPTY),ItemSlotChange(b,ItemStackSnapshot.EMPTY,item)))))
    private fun phase(record: ItemRollbackRecord, phase: ItemRollbackPhase) = ItemRollbackRecord(record.operationId,record.createdAt,phase,record.entries)

    private inner class Journal(var value: ItemRollbackRecord?) : ItemApplyJournalPort {
        val reads = mutableListOf<CompletableFuture<ItemRollbackRecord?>>()
        val marks = mutableListOf<CompletableFuture<Boolean>>()
        override fun read(operationId: UUID): CompletionStage<ItemRollbackRecord?> = CompletableFuture<ItemRollbackRecord?>().also { reads.add(it) }
        override fun markApplying(operationId: UUID): CompletionStage<Boolean> = CompletableFuture<Boolean>().also { marks.add(it) }
        fun finish() {
            reads.filter { !it.isDone }.forEach { it.complete(value) }
            marks.filter { !it.isDone }.forEach {
                val current=value
                if(current?.phase==ItemRollbackPhase.PREPARED) {value=phase(current,ItemRollbackPhase.APPLYING);it.complete(true)} else it.complete(false)
            }
        }
    }
    private inner class Port : ItemApplyPort {
        val live = linkedMapOf(a to ItemStackSnapshot.EMPTY,b to item,spare to ItemStackSnapshot.EMPTY)
        var exclusive=true
        var unavailable=false
        var writes=0
        var onRead: () -> Unit = {}
        var onWrite: (ItemSlotAddress) -> Unit = {}
        override fun isExclusiveAndCurrent(owners: Set<ItemSlotOwner>)=exclusive
        override fun readOwners(owners: Set<ItemSlotOwner>): InventorySnapshot? {onRead();return if(unavailable) null else InventorySnapshot(live)}
        override fun writeSlot(address: ItemSlotAddress,expected: ItemStackSnapshot,replacement: ItemStackSnapshot) {
            check(live[address]==expected);writes++;live[address]=replacement;onWrite(address)
        }
    }
    private fun pump(driver: ItemApplyDriver,journal: Journal) {
        repeat(30) { if(driver.state !in setOf(ItemApplyState.WRITTEN,ItemApplyState.UNRESOLVED)) {driver.advance();journal.finish()} }
    }
    private fun reachWriting(driver: ItemApplyDriver,journal: Journal) {
        repeat(20) { if(driver.state !in setOf(ItemApplyState.WRITING,ItemApplyState.UNRESOLVED)) {driver.advance();journal.finish()} }
        assertEquals(ItemApplyState.WRITING,driver.state)
    }

    @Test fun noSetterRunsUntilIntentAndFreshApplyingRecordAreConfirmed() {
        val record=record();val journal=Journal(record);val port=Port();val driver=ItemApplyDriver(record,journal,port)
        driver.advance();repeat(3){driver.advance()};assertEquals(1,journal.reads.size);assertEquals(0,port.writes)
        journal.finish();driver.advance();assertEquals(ItemApplyState.MARKING_APPLYING,driver.state)
        repeat(3){driver.advance()};assertEquals(0,port.writes)
        journal.finish();driver.advance();driver.advance();assertEquals(2,journal.reads.size);assertEquals(0,port.writes)
        journal.finish();driver.advance();assertEquals(ItemApplyState.WRITING,driver.state);assertEquals(0,port.writes)
        assertNull(driver.applyingRecord);driver.advance();assertEquals(1,port.writes)
    }
    @Test fun writtenStatePreservesJournalClaimsAndUnchangedSlotsForSaveHandoff() {
        val record=record();val journal=Journal(record);val port=Port();val driver=ItemApplyDriver(record,journal,port)
        pump(driver,journal);assertEquals(ItemApplyState.WRITTEN,driver.state);assertEquals(2,port.writes);assertEquals(2,driver.attemptedSlots)
        assertEquals(item,port.live[a]);assertEquals(ItemStackSnapshot.EMPTY,port.live[b]);assertEquals(ItemStackSnapshot.EMPTY,port.live[spare])
        assertEquals(ItemRollbackPhase.APPLYING,journal.value?.phase);assertEquals(ItemRollbackPhase.APPLYING,driver.applyingRecord?.phase)
        driver.advance();driver.stop();assertEquals(2,port.writes);assertEquals(1,journal.marks.size)
    }
    @Test fun missingOrChangedOriginalContentsRefuseBeforeJournalAccess() {
        for(kind in 0..2) {
            val record=record();val journal=Journal(record);val port=Port()
            when(kind) {0->port.live.remove(a);1->port.live[b]=ItemStackSnapshot.EMPTY;else->port.unavailable=true}
            val driver=ItemApplyDriver(record,journal,port);assertEquals(ItemApplyState.UNRESOLVED,driver.advance());assertEquals(0,port.writes);assertTrue(journal.reads.isEmpty())
        }
    }
    @Test fun unchangedSlotMutationWhileWaitingIsDetected() {
        val journal=Journal(record());val port=Port();val driver=ItemApplyDriver(journal.value!!,journal,port)
        driver.advance();port.live[spare]=item;journal.finish();assertEquals(ItemApplyState.UNRESOLVED,driver.advance());assertEquals(0,port.writes);assertTrue(journal.marks.isEmpty())
    }
    @Test fun identityLossInsideReadPreventsJournalAndSetters() {
        val journal=Journal(record());val port=Port().apply {onRead={exclusive=false}};val driver=ItemApplyDriver(journal.value!!,journal,port)
        assertEquals(ItemApplyState.UNRESOLVED,driver.advance());assertEquals(0,port.writes);assertTrue(journal.reads.isEmpty())
    }
    @Test fun ownershipLossDuringIntentWaitDoesNotCancelLateJournalWrite() {
        val journal=Journal(record());val port=Port();val driver=ItemApplyDriver(journal.value!!,journal,port)
        driver.advance();journal.finish();driver.advance();port.exclusive=false;assertEquals(ItemApplyState.UNRESOLVED,driver.advance())
        assertFalse(journal.marks.single().isCancelled);journal.finish();driver.advance();assertEquals(ItemRollbackPhase.APPLYING,journal.value?.phase);assertEquals(0,port.writes)
    }
    @Test fun changedPreparedPhaseIdentityOrPayloadIsRefused() {
        for(kind in 0..3) {
            val record=record();val journal=Journal(record);val port=Port();val driver=ItemApplyDriver(record,journal,port);driver.advance()
            journal.value=when(kind) {
                0->phase(record,ItemRollbackPhase.CANCELLED)
                1->ItemRollbackRecord(UUID.randomUUID(),record.createdAt,record.phase,record.entries)
                2->ItemRollbackRecord(record.operationId,record.createdAt+1,record.phase,record.entries)
                else->ItemRollbackRecord(record.operationId,record.createdAt,record.phase,listOf(ItemRollbackEntry(UUID.randomUUID(),record.entries.single().changes)))
            }
            journal.finish();assertEquals(ItemApplyState.UNRESOLVED,driver.advance());assertEquals(0,port.writes);assertTrue(journal.marks.isEmpty())
        }
    }
    @Test fun falseOrExceptionalIntentAcknowledgementNeverStartsWriting() {
        for(failure in listOf(false,true)) {
            val journal=Journal(record());val port=Port();val driver=ItemApplyDriver(journal.value!!,journal,port)
            driver.advance();journal.finish();driver.advance()
            if(failure) journal.marks.single().completeExceptionally(IllegalStateException("Disk full")) else journal.marks.single().complete(false)
            assertEquals(ItemApplyState.UNRESOLVED,driver.advance());assertEquals(0,port.writes)
        }
    }
    @Test fun missingFailedOrChangedApplyingReadRefusesBeforeFirstSetter() {
        for(kind in 0..2) {
            val record=record();val journal=Journal(record);val port=Port();val driver=ItemApplyDriver(record,journal,port)
            driver.advance();journal.finish();driver.advance();journal.finish();driver.advance();driver.advance()
            when(kind) {0->journal.reads.last().complete(null);1->journal.reads.last().completeExceptionally(IllegalStateException("Read failed"));else->journal.reads.last().complete(phase(record,ItemRollbackPhase.RECOVERY_REQUIRED))}
            assertEquals(ItemApplyState.UNRESOLVED,driver.advance());assertEquals(0,port.writes)
        }
    }
    @Test fun setterFailureAfterMutationLeavesApplyingForRecoveryAndNeverRetries() {
        val journal=Journal(record());val port=Port().apply {onWrite={error("Failure after setter")}};val driver=ItemApplyDriver(journal.value!!,journal,port)
        reachWriting(driver,journal);assertEquals(ItemApplyState.UNRESOLVED,driver.advance());assertEquals(1,port.writes);assertEquals(1,driver.attemptedSlots);assertNull(driver.applyingRecord)
        assertEquals(ItemRollbackPhase.APPLYING,journal.value?.phase);assertNull(driver.applyingRecord);driver.advance();assertEquals(1,port.writes)
    }
    @Test fun setterSideEffectOnAnotherSlotOrOwnershipIsDetected() {
        for(identity in listOf(false,true)) {
            val journal=Journal(record());val port=Port().apply {onWrite={if(identity) exclusive=false else live[spare]=item}};val driver=ItemApplyDriver(journal.value!!,journal,port)
            reachWriting(driver,journal);assertEquals(ItemApplyState.UNRESOLVED,driver.advance());assertEquals(1,port.writes)
        }
    }
    @Test fun finalJournalChangeRefusesWrittenResultAfterSetters() {
        val record=record();val journal=Journal(record);val port=Port();val driver=ItemApplyDriver(record,journal,port)
        reachWriting(driver,journal);driver.advance();driver.advance();assertEquals(ItemApplyState.CHECKING_FINAL_JOURNAL,driver.state)
        driver.advance();journal.value=phase(record,ItemRollbackPhase.RECOVERY_REQUIRED);journal.finish();assertEquals(ItemApplyState.UNRESOLVED,driver.advance());assertEquals(2,port.writes)
    }
    @Test fun timeoutAndStopIgnoreLateJournalResultsWithoutCancellingThem() {
        for(timeout in listOf(false,true)) {
            var now=0L;val journal=Journal(record());val port=Port();val driver=ItemApplyDriver(journal.value!!,journal,port){now}
            driver.advance();if(timeout){now=TimeUnit.SECONDS.toNanos(10);driver.advance()}else driver.stop()
            assertEquals(ItemApplyState.UNRESOLVED,driver.state);assertFalse(journal.reads.single().isCancelled);journal.finish();driver.advance();assertEquals(0,port.writes)
        }
    }
    @Test fun backgroundCompletionCannotAdvanceDriverAndWrongThreadIsRefused() {
        val journal=Journal(record());val port=Port();val driver=ItemApplyDriver(journal.value!!,journal,port);driver.advance()
        CompletableFuture.runAsync {journal.reads.single().complete(journal.value);assertFailsWith<IllegalStateException>{driver.advance()};assertFailsWith<IllegalStateException>{driver.stop()}}.get(5,TimeUnit.SECONDS)
        assertEquals(ItemApplyState.CHECKING_PREPARED,driver.state);assertEquals(0,port.writes)
    }
    @Test fun netNoopHistoryStillRequiresIntentAndFinalJournalWithoutSetters() {
        val first=record();val changes=first.entries.single().changes;val reverse=ItemRollbackEntry(UUID.randomUUID(),changes.map {ItemSlotChange(it.address,it.after,it.before)})
        val record=ItemRollbackRecord(first.operationId,1,ItemRollbackPhase.PREPARED,listOf(reverse,first.entries.single()))
        val journal=Journal(record);val port=Port().apply {live[a]=item;live[b]=ItemStackSnapshot.EMPTY};val driver=ItemApplyDriver(record,journal,port)
        pump(driver,journal);assertEquals(ItemApplyState.WRITTEN,driver.state);assertEquals(0,port.writes);assertEquals(ItemRollbackPhase.APPLYING,journal.value?.phase)
    }
    @Test fun invalidPhasesOwnersAndNonconservingPlansAreRejected() {
        for(phase in ItemRollbackPhase.entries.filter {it!=ItemRollbackPhase.PREPARED}) {
            val record=record(phase);assertFailsWith<IllegalArgumentException>{ItemApplyDriver(record,Journal(record),Port())}
        }
        val invalid=ItemRollbackRecord(UUID.randomUUID(),1,ItemRollbackPhase.PREPARED,listOf(ItemRollbackEntry(UUID.randomUUID(),listOf(ItemSlotChange(a,item,ItemStackSnapshot.EMPTY)))))
        assertFailsWith<IllegalArgumentException>{ItemApplyDriver(invalid,Journal(invalid),Port())}
    }
    @Test fun oversizedOrExtraOwnerImagesAreRefusedWithoutJournalAccess() {
        for(extra in listOf(false,true)) {
            val record=record();val journal=Journal(record);val port=Port()
            if(extra) port.live[ItemSlotAddress(ItemSlotOwner.PlayerInventory(UUID.randomUUID()),0)]=item
            else for(index in 2..2049) port.live[ItemSlotAddress(a.owner,index)]=ItemStackSnapshot.EMPTY
            assertEquals(ItemApplyState.UNRESOLVED,ItemApplyDriver(record,journal,port).advance());assertEquals(0,port.writes);assertTrue(journal.reads.isEmpty())
        }
    }
}
