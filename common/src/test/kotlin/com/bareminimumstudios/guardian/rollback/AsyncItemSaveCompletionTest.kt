package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import java.util.concurrent.*
import kotlin.test.*

class AsyncItemSaveCompletionTest {
    private val a = ItemSlotAddress(ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"), BlockPosition(1,64,1)),0)
    private val b = ItemSlotAddress(ItemSlotOwner.PlayerInventory(UUID.randomUUID()),0)
    private val item = ItemStackSnapshot(ResourceId.parse("minecraft:coal"),1,BinaryPayload.of(byteArrayOf(1)))
    private fun record(phase: ItemRollbackPhase = ItemRollbackPhase.APPLYING) = ItemRollbackRecord(UUID.randomUUID(),1,phase,listOf(ItemRollbackEntry(UUID.randomUUID(),listOf(ItemSlotChange(a,item,ItemStackSnapshot.EMPTY),ItemSlotChange(b,ItemStackSnapshot.EMPTY,item)))))
    private inner class Port : ItemSavePort {
        val thread = Thread.currentThread()
        var exclusive = true
        var wrong = false
        var calls = 0
        override fun isExclusiveAndCurrent(owners: Set<ItemSlotOwner>): Boolean { assertSame(thread,Thread.currentThread()); return exclusive }
        override fun saveAndReadBack(owner: ItemSlotOwner, addresses: Set<ItemSlotAddress>): CompletionStage<InventorySnapshot?> {
            assertSame(thread,Thread.currentThread()); calls++
            return CompletableFuture.completedFuture(InventorySnapshot(addresses.associateWith { if (it==a && !wrong) item else ItemStackSnapshot.EMPTY }))
        }
    }
    private class Journal(var value: ItemRollbackRecord?) : ItemSaveJournalPort {
        var reads = 0
        var commits = 0
        var pendingRead: CompletableFuture<ItemRollbackRecord?>? = null
        var pendingCommit: CompletableFuture<Boolean>? = null
        override fun read(operationId: UUID): CompletionStage<ItemRollbackRecord?> { reads++; return pendingRead ?: CompletableFuture.completedFuture(value) }
        override fun complete(record: ItemRollbackRecord): CompletionStage<Boolean> {
            commits++
            pendingCommit?.let { return it }
            value = ItemRollbackRecord(record.operationId,record.createdAt,ItemRollbackPhase.COMPLETED,record.entries)
            return CompletableFuture.completedFuture(true)
        }
    }
    private fun drain(driver: AsyncItemSaveCompletion): AsyncItemSaveState { repeat(40) { driver.advance() }; return driver.state }
    @Test fun everyOwnerSaveAndFreshCompletedRecordAreRequired() {
        for (phase in listOf(ItemRollbackPhase.APPLYING,ItemRollbackPhase.RECOVERY_REQUIRED)) {
            val record=record(phase); val journal=Journal(record); val port=Port(); val driver=AsyncItemSaveCompletion(record,journal,port)
            assertEquals(AsyncItemSaveState.COMPLETED,drain(driver)); assertEquals(2,port.calls); assertEquals(1,journal.commits); assertEquals(4,journal.reads)
            driver.stop(); driver.advance(); assertEquals(1,journal.commits)
        }
    }
    @Test fun incompleteJournalFutureNeverStartsInventorySaves() {
        val record=record();val journal=Journal(record).apply { pendingRead=CompletableFuture() }; val port=Port();val driver=AsyncItemSaveCompletion(record,journal,port)
        drain(driver);assertEquals(0,port.calls);assertEquals(1,journal.reads)
        journal.pendingRead!!.complete(record);journal.pendingRead=null;assertEquals(AsyncItemSaveState.COMPLETED,drain(driver))
    }
    @Test fun wrongSavedContentsOrOwnershipNeverSubmitCompletion() {
        for (wrong in listOf(false,true)) {
            val record=record(); val journal=Journal(record); val port=Port().apply { exclusive=wrong;this.wrong=wrong };val driver=AsyncItemSaveCompletion(record,journal,port)
            assertEquals(AsyncItemSaveState.UNRESOLVED,drain(driver));assertEquals(0,journal.commits)
        }
    }
    @Test fun changedPayloadBeforeCompletionIsRefused() {
        val record=record();val journal=Journal(record);val driver=AsyncItemSaveCompletion(record,journal,Port())
        while(driver.state!=AsyncItemSaveState.CHECKING_FINAL_JOURNAL) driver.advance()
        journal.value=ItemRollbackRecord(record.operationId,2,record.phase,record.entries)
        assertEquals(AsyncItemSaveState.UNRESOLVED,drain(driver));assertEquals(0,journal.commits)
    }
    @Test fun positiveCommitAcknowledgementStillRequiresCompletedReadback() {
        val record=record();val journal=Journal(record).apply { pendingCommit=CompletableFuture.completedFuture(true) };val driver=AsyncItemSaveCompletion(record,journal,Port())
        assertEquals(AsyncItemSaveState.UNRESOLVED,drain(driver));assertEquals(1,journal.commits);assertTrue(driver.reason!!.contains("reconciled"))
    }
    @Test fun stopOrTimeoutDoesNotCancelOrHideSubmittedCommit() {
        for (timeout in listOf(false,true)) {
            var now=0L;val record=record();val journal=Journal(record).apply { pendingCommit=CompletableFuture() };val driver=AsyncItemSaveCompletion(record,journal,Port()) { now }
            while(driver.state!=AsyncItemSaveState.COMMITTING) driver.advance()
            if(timeout) {now=TimeUnit.SECONDS.toNanos(10);driver.advance()} else driver.stop()
            assertEquals(AsyncItemSaveState.UNRESOLVED,driver.state);assertTrue(driver.reason!!.contains("reconciled"));assertFalse(journal.pendingCommit!!.isCancelled)
            journal.pendingCommit!!.complete(true);assertTrue(driver.completionAttempt!!.toCompletableFuture().join());assertEquals(AsyncItemSaveState.UNRESOLVED,driver.advance());assertEquals(1,journal.commits)
        }
    }
    @Test fun failedCommitAcknowledgementIsUnresolvedAndNeverRetried() {
        for(result in listOf(CompletableFuture.completedFuture(false),CompletableFuture.failedFuture<Boolean>(IllegalStateException("Disk failure")))) {
            val record=record();val journal=Journal(record).apply { pendingCommit=result };val driver=AsyncItemSaveCompletion(record,journal,Port())
            assertEquals(AsyncItemSaveState.UNRESOLVED,drain(driver));assertEquals(1,journal.commits)
        }
    }
    @Test fun stoppedReadAndForeignDriverCannotAdvance() {
        val record=record();val journal=Journal(record).apply { pendingRead=CompletableFuture() };val port=Port();val driver=AsyncItemSaveCompletion(record,journal,port)
        driver.advance();CompletableFuture.runAsync { assertFailsWith<IllegalStateException>{driver.advance()};assertFailsWith<IllegalStateException>{driver.stop()} }.get(5,TimeUnit.SECONDS)
        driver.stop();journal.pendingRead!!.complete(record);assertEquals(AsyncItemSaveState.UNRESOLVED,drain(driver));assertEquals(0,port.calls)
    }
    private class WorkerJournal(var value: ItemRollbackRecord) : ItemRollbackJournal {
        val driver=Thread.currentThread();var calls=0
        override fun itemRollback(operationId: UUID): ItemRollbackRecord {check(Thread.currentThread()!==driver);calls++;return value}
        override fun transitionItemRollback(operationId: UUID,expected: ItemRollbackPhase,next: ItemRollbackPhase): Boolean {check(Thread.currentThread()!==driver);calls++;if(value.phase!=expected)return false;value=ItemRollbackRecord(value.operationId,value.createdAt,next,value.entries);return true}
        override fun prepareItemRollback(operationId: UUID,createdAt: Long,newestFirst: List<ContainerTransactionSnapshot>): ItemRollbackRecord=error("No prepare")
        override fun unfinishedItemRollbacks(limit: Int): List<ItemRollbackSummary> = error("No listing")
    }
    @Test fun workerAdapterReadsAndCompletesOffInventoryThread() {
        val record=record();val journal=WorkerJournal(record);val worker=Executors.newSingleThreadExecutor();val adapter=AsyncItemSaveJournal(journal,worker)
        try { assertSame(record,adapter.read(record.operationId).toCompletableFuture().get(5,TimeUnit.SECONDS));assertTrue(adapter.complete(record).toCompletableFuture().get(5,TimeUnit.SECONDS));assertEquals(3,journal.calls) } finally {worker.shutdownNow()}
    }
    @Test fun fullDriverUsesWorkerJournalAndMainThreadInventoryCalls() {
        val record=record();val journal=WorkerJournal(record);val worker=Executors.newSingleThreadExecutor();val port=Port()
        try {
            val driver=AsyncItemSaveCompletion(record,AsyncItemSaveJournal(journal,worker),port)
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(driver.state!=AsyncItemSaveState.COMPLETED && driver.state!=AsyncItemSaveState.UNRESOLVED && System.nanoTime()<deadline) {driver.advance();Thread.yield()}
            assertEquals(AsyncItemSaveState.COMPLETED,driver.state);assertEquals(2,port.calls);assertEquals(ItemRollbackPhase.COMPLETED,journal.value.phase);assertEquals(6,journal.calls)
        } finally {worker.shutdownNow()}
    }
    @Test fun workerRejectsChangedFullRecordWithoutTransition() {
        val record=record();val journal=WorkerJournal(ItemRollbackRecord(record.operationId,2,record.phase,record.entries));val worker=Executors.newSingleThreadExecutor()
        try {assertFalse(AsyncItemSaveJournal(journal,worker).complete(record).toCompletableFuture().get(5,TimeUnit.SECONDS));assertEquals(1,journal.calls)} finally {worker.shutdownNow()}
    }
    @Test fun inlineAndRejectedExecutorsCannotTouchJournal() {
        for(executor in listOf(Executor { it.run() },Executor { throw RejectedExecutionException("Full queue") })) {
            val record=record();val journal=WorkerJournal(record);val adapter=AsyncItemSaveJournal(journal,executor)
            assertFailsWith<CompletionException>{adapter.read(record.operationId).toCompletableFuture().join()};assertFailsWith<CompletionException>{adapter.complete(record).toCompletableFuture().join()};assertEquals(0,journal.calls)
        }
    }
    @Test fun foreignAdapterThreadAndInvalidPhaseNeverSubmitWork() {
        val record=record();val journal=WorkerJournal(record);val adapter=AsyncItemSaveJournal(journal,Executor { error("Must not submit") })
        CompletableFuture.runAsync { assertFailsWith<IllegalStateException>{adapter.read(record.operationId)};assertFailsWith<IllegalStateException>{adapter.complete(record)} }.get(5,TimeUnit.SECONDS)
        assertFailsWith<IllegalArgumentException>{adapter.complete(record(ItemRollbackPhase.PREPARED))};assertEquals(0,journal.calls)
    }
}
