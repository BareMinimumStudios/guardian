package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ItemSaveCompletionTest {
    private val dimension=ResourceId.parse("minecraft:overworld")
    private val a=ItemSlotAddress(ItemSlotOwner.BlockContainer(dimension,BlockPosition(1,64,1)),0)
    private val b=ItemSlotAddress(ItemSlotOwner.PlayerInventory(UUID.randomUUID()),0)
    private val item=ItemStackSnapshot(ResourceId.parse("minecraft:coal"),1,BinaryPayload.of(byteArrayOf(1)))
    private fun record(phase: ItemRollbackPhase=ItemRollbackPhase.APPLYING)=ItemRollbackRecord(UUID.randomUUID(),1,phase,listOf(ItemRollbackEntry(UUID.randomUUID(),listOf(ItemSlotChange(a,item,ItemStackSnapshot.EMPTY),ItemSlotChange(b,ItemStackSnapshot.EMPTY,item)))))
    private class Journal(var value: ItemRollbackRecord?): ItemRollbackJournal {
        var transitions=0;var accept=true;var throwing=false
        override fun prepareItemRollback(operationId: UUID,createdAt: Long,newestFirst: List<ContainerTransactionSnapshot>): ItemRollbackRecord=error("No prepare during save completion")
        override fun itemRollback(operationId: UUID)=value
        override fun unfinishedItemRollbacks(limit: Int)=value?.let { listOf(ItemRollbackSummary(it.operationId,it.createdAt,it.phase)) } ?: emptyList()
        override fun transitionItemRollback(operationId: UUID,expected: ItemRollbackPhase,next: ItemRollbackPhase): Boolean {
            transitions++;if(throwing) error("Synthetic transition failure")
            if(!accept || value?.phase!=expected) return false
            value=value!!.let { ItemRollbackRecord(it.operationId,it.createdAt,next,it.entries) };return true
        }
    }
    private inner class Port: ItemSavePort {
        var exclusive=true;var calls=0
        val results=mutableListOf<CompletableFuture<InventorySnapshot?>>()
        val addresses=mutableListOf<Set<ItemSlotAddress>>()
        override fun isExclusiveAndCurrent(owners: Set<ItemSlotOwner>)=exclusive
        override fun saveAndReadBack(owner: ItemSlotOwner,addresses: Set<ItemSlotAddress>): CompletionStage<InventorySnapshot?> {
            calls++;this.addresses.add(addresses);return CompletableFuture<InventorySnapshot?>().also { results.add(it) }
        }
        fun complete(index: Int) { results[index].complete(InventorySnapshot(addresses[index].associateWith { if(it==a) item else ItemStackSnapshot.EMPTY })) }
    }
    @Test fun completesOnlyAfterEveryOwnerSaveReadbackAndFinalJournalCheck() {
        val record=record();val journal=Journal(record);val port=Port();val driver=ItemSaveCompletion(record,journal,port)
        assertEquals(ItemSaveCompletionState.CHECKING,driver.advance());assertEquals(1,port.calls);assertEquals(0,journal.transitions)
        driver.advance();assertEquals(1,port.calls);port.complete(0);driver.advance();assertEquals(0,journal.transitions)
        driver.advance();assertEquals(2,port.calls);port.complete(1);assertEquals(ItemSaveCompletionState.COMPLETED,driver.advance());assertEquals(1,journal.transitions)
        driver.advance();driver.stop();assertEquals(1,journal.transitions)
    }
    @Test fun rejectsMissingOriginalAndWrongComponentReadback() {
        for(snapshot in listOf(null,InventorySnapshot(emptyMap()),InventorySnapshot(mapOf(a to ItemStackSnapshot.EMPTY)),InventorySnapshot(mapOf(a to item.copy(itemData=BinaryPayload.of(byteArrayOf(2))))))) {
            val record=record();val journal=Journal(record);val port=Port();val driver=ItemSaveCompletion(record,journal,port)
            driver.advance();port.results[0].complete(snapshot);assertEquals(ItemSaveCompletionState.UNRESOLVED,driver.advance());assertEquals(0,journal.transitions);assertEquals(ItemRollbackPhase.APPLYING,journal.value?.phase)
        }
    }
    @Test fun partialSaveFailureLeavesJournalAndClaimsForRecovery() {
        val record=record();val journal=Journal(record);val port=Port();val driver=ItemSaveCompletion(record,journal,port)
        driver.advance();port.complete(0);driver.advance();driver.advance();port.results[1].completeExceptionally(IllegalStateException("Disk full"))
        assertEquals(ItemSaveCompletionState.UNRESOLVED,driver.advance());assertEquals(0,journal.transitions);assertEquals(record,journal.value)
    }
    @Test fun ownershipLossWhileWaitingStopsFurtherSaves() {
        val record=record();val journal=Journal(record);val port=Port();val driver=ItemSaveCompletion(record,journal,port)
        driver.advance();port.exclusive=false;assertEquals(ItemSaveCompletionState.UNRESOLVED,driver.advance());port.complete(0);driver.advance();assertEquals(1,port.calls);assertEquals(0,journal.transitions)
    }
    @Test fun noExclusiveOwnershipMeansNoSaveAttempt() {
        val record=record();val journal=Journal(record);val port=Port().apply { exclusive=false };val driver=ItemSaveCompletion(record,journal,port)
        assertEquals(ItemSaveCompletionState.UNRESOLVED,driver.advance());assertEquals(0,port.calls);assertEquals(0,journal.transitions)
    }
    @Test fun changedJournalPhasePayloadOrIdentityRefusesCompletion() {
        for(kind in 0..3) {
            val record=record();val journal=Journal(record);val port=Port();val driver=ItemSaveCompletion(record,journal,port)
            driver.advance();port.complete(0);driver.advance();driver.advance();port.complete(1)
            journal.value=when(kind) {
                0 -> ItemRollbackRecord(record.operationId,record.createdAt,ItemRollbackPhase.RECOVERY_REQUIRED,record.entries)
                1 -> ItemRollbackRecord(record.operationId,record.createdAt+1,record.phase,record.entries)
                2 -> ItemRollbackRecord(UUID.randomUUID(),record.createdAt,record.phase,record.entries)
                else -> ItemRollbackRecord(record.operationId,record.createdAt,record.phase,listOf(ItemRollbackEntry(UUID.randomUUID(),record.entries.single().changes)))
            }
            assertEquals(ItemSaveCompletionState.UNRESOLVED,driver.advance());assertEquals(0,journal.transitions)
        }
    }
    @Test fun staleOrFailedCompletionAcknowledgementStaysUnresolved() {
        for(failure in 0..1) {
            val record=record();val journal=Journal(record).apply { accept=false;throwing=failure==1 };val port=Port();val driver=ItemSaveCompletion(record,journal,port)
            driver.advance();port.complete(0);driver.advance();driver.advance();port.complete(1)
            assertEquals(ItemSaveCompletionState.UNRESOLVED,driver.advance());assertEquals(1,journal.transitions);driver.advance();assertEquals(1,journal.transitions)
        }
    }
    @Test fun timeoutAndStopIgnoreLateSaveResultsWithoutCancellingTheirWrites() {
        for(timeout in listOf(false,true)) {
            var now=0L;val record=record();val journal=Journal(record);val port=Port();val driver=ItemSaveCompletion(record,journal,port) { now }
            driver.advance();if(timeout) { now=TimeUnit.SECONDS.toNanos(10);driver.advance() } else driver.stop()
            assertEquals(ItemSaveCompletionState.UNRESOLVED,driver.state);assertFalse(port.results[0].isCancelled);port.complete(0);driver.advance();assertEquals(0,journal.transitions)
        }
    }
    @Test fun rejectsPreparedTerminalAndInvalidPlans() {
        for(phase in listOf(ItemRollbackPhase.PREPARED,ItemRollbackPhase.COMPLETED,ItemRollbackPhase.CANCELLED)) {
            val record=record(phase);assertFailsWith<IllegalArgumentException> { ItemSaveCompletion(record,Journal(record),Port()) }
        }
        val record=record();val empty=ItemRollbackRecord(record.operationId,1,record.phase,emptyList());assertFailsWith<IllegalArgumentException> { ItemSaveCompletion(empty,Journal(empty),Port()) }
    }
    @Test fun recoveredJournalCanCompleteAfterFreshSaveReadbacks() {
        val record=record(ItemRollbackPhase.RECOVERY_REQUIRED);val journal=Journal(record);val port=Port();val driver=ItemSaveCompletion(record,journal,port)
        driver.advance();port.complete(0);driver.advance();driver.advance();port.complete(1);assertEquals(ItemSaveCompletionState.COMPLETED,driver.advance());assertEquals(ItemRollbackPhase.COMPLETED,journal.value?.phase)
    }
    @Test fun backgroundSaveCallbackCannotAdvanceJournalOnItsOwn() {
        val record=record();val journal=Journal(record);val port=Port();val driver=ItemSaveCompletion(record,journal,port);driver.advance()
        CompletableFuture.runAsync { port.complete(0) }.get(2,TimeUnit.SECONDS);assertEquals(0,journal.transitions);assertEquals(ItemSaveCompletionState.CHECKING,driver.state);driver.advance();assertEquals(0,journal.transitions)
        val error=CompletableFuture.supplyAsync { runCatching { driver.advance() }.exceptionOrNull() }.get(2,TimeUnit.SECONDS);assertIs<IllegalStateException>(error);driver.stop()
    }
    @Test fun synchronousSavePortFailurePreservesUnfinishedJournal() {
        val record=record();val journal=Journal(record)
        val port=object: ItemSavePort { override fun isExclusiveAndCurrent(owners: Set<ItemSlotOwner>)=true;override fun saveAndReadBack(owner: ItemSlotOwner,addresses: Set<ItemSlotAddress>): CompletionStage<InventorySnapshot?> = error("Synthetic save failure") }
        assertEquals(ItemSaveCompletionState.UNRESOLVED,ItemSaveCompletion(record,journal,port).advance());assertEquals(0,journal.transitions)
    }
}
