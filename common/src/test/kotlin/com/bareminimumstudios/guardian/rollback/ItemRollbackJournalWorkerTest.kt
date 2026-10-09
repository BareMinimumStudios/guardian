package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ItemRollbackJournalWorkerTest {
    private val a=ItemSlotAddress(ItemSlotOwner.PlayerInventory(UUID.randomUUID()),0)
    private val b=ItemSlotAddress(ItemSlotOwner.PlayerInventory(UUID.randomUUID()),0)
    private val item=ItemStackSnapshot(ResourceId.parse("minecraft:coal"),1,BinaryPayload.of(byteArrayOf(1)))
    private fun record(phase: ItemRollbackPhase=ItemRollbackPhase.APPLYING)=ItemRollbackRecord(UUID.randomUUID(),1,phase,listOf(ItemRollbackEntry(UUID.randomUUID(),listOf(ItemSlotChange(a,item,ItemStackSnapshot.EMPTY),ItemSlotChange(b,ItemStackSnapshot.EMPTY,item)))))
    private class Journal(var value: ItemRollbackRecord) : ItemRollbackJournal {
        val driver=Thread.currentThread();val calls=AtomicInteger();val transitions=AtomicInteger()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        var blockRead=false;var blockTransition=false;var fail=false
        override fun itemRollback(operationId: UUID): ItemRollbackRecord {
            check(Thread.currentThread()!==driver);calls.incrementAndGet()
            if(blockRead) { entered.countDown();check(release.await(5,TimeUnit.SECONDS)) }
            if(fail) error("Read failed")
            return value
        }
        override fun transitionItemRollback(operationId: UUID,expected: ItemRollbackPhase,next: ItemRollbackPhase): Boolean {
            check(Thread.currentThread()!==driver);transitions.incrementAndGet()
            if(blockTransition) {entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
            if(fail) error("Transition failed")
            if(value.phase!=expected)return false
            value=ItemRollbackRecord(value.operationId,value.createdAt,next,value.entries);return true
        }
        override fun prepareItemRollback(operationId: UUID,createdAt: Long,newestFirst: List<ContainerTransactionSnapshot>): ItemRollbackRecord=error("No prepare")
        override fun unfinishedItemRollbacks(limit: Int): List<ItemRollbackSummary> = error("No list")
    }
    private fun <T> CompletionStage<T>.await(): T=toCompletableFuture().get(5,TimeUnit.SECONDS)
    private fun finish(worker: ItemRollbackJournalWorker,journal: Journal) {worker.close();journal.release.countDown();worker.drained.await()}
    @Test fun recoveryClassificationReadsFreshPlanAndNeverCompletesOrReplaysIt() {
        val record=record();val journal=Journal(record);val worker=ItemRollbackJournalWorker(journal)
        try {
            assertTrue(worker.markRecovery(record).await())
            assertEquals(ItemRollbackPhase.RECOVERY_REQUIRED,journal.value.phase)
            assertFalse(worker.markRecovery(record).await());assertEquals(1,journal.transitions.get())
        } finally {finish(worker,journal)}
    }
    @Test fun changedPlanCannotBeClassifiedAsThisInterruptedOperation() {
        val record=record();val journal=Journal(record()).apply {value=ItemRollbackRecord(record.operationId,record.createdAt+1,record.phase,record.entries)}
        val worker=ItemRollbackJournalWorker(journal)
        try {assertFalse(worker.markRecovery(record).await());assertEquals(0,journal.transitions.get())}
        finally {finish(worker,journal)}
    }
    @Test fun startedRecoveryClassificationMustPhysicallyDrainAfterStop() {
        val record=record();val journal=Journal(record).apply {blockTransition=true};val worker=ItemRollbackJournalWorker(journal)
        try {
            val result=worker.markRecovery(record);assertTrue(journal.entered.await(5,TimeUnit.SECONDS));worker.close()
            result.toCompletableFuture().cancel(false);assertFalse(worker.drained.toCompletableFuture().isDone)
            journal.release.countDown();worker.drained.await();assertEquals(ItemRollbackPhase.RECOVERY_REQUIRED,journal.value.phase)
        } finally {finish(worker,journal)}
    }
    @Test fun recoveryClassificationRejectsPreparedAndStoppedAdmission() {
        val record=record();val journal=Journal(record);val worker=ItemRollbackJournalWorker(journal)
        assertFailsWith<IllegalArgumentException>{worker.markRecovery(record(ItemRollbackPhase.PREPARED))}
        worker.close();worker.drained.await()
        assertFailsWith<ExecutionException>{worker.markRecovery(record).await()};assertEquals(0,journal.transitions.get())
    }

    @Test fun unsupportedProtectionFailsClosedWithoutJournalCalls() {
        val record=record(ItemRollbackPhase.PREPARED);val journal=Journal(record);val worker=ItemRollbackJournalWorker(journal)
        try {assertFalse(worker.protect(record).await());assertEquals(0,journal.calls.get());assertEquals(0,journal.transitions.get())}
        finally {finish(worker,journal)}
    }
    @Test fun stoppedProtectionCannotSubmitOrRegisterAnything() {
        val record=record(ItemRollbackPhase.PREPARED);val journal=Journal(record);val worker=ItemRollbackJournalWorker(journal)
        worker.close();worker.drained.await()
        assertFailsWith<ExecutionException>{worker.protect(record).await()};assertEquals(0,journal.calls.get());assertEquals(0,journal.transitions.get())
    }
    @Test fun oneWorkerSupportsBothJournalProtocols() {
        val record=record(ItemRollbackPhase.PREPARED);val journal=Journal(record);val worker=ItemRollbackJournalWorker(journal)
        try {assertSame(record,worker.read(record.operationId).await());assertTrue(worker.markApplying(record.operationId).await());assertTrue(worker.complete(worker.read(record.operationId).await()!!).await());assertEquals(ItemRollbackPhase.COMPLETED,worker.read(record.operationId).await()?.phase)} finally {finish(worker,journal)}
    }
    @Test fun stoppedQueueNeverStartsCompletionAndRunningReadDrains() {
        val record=record();val journal=Journal(record).apply {blockRead=true};val worker=ItemRollbackJournalWorker(journal)
        try {
            val running=worker.read(record.operationId);assertTrue(journal.entered.await(5,TimeUnit.SECONDS));val queued=worker.complete(record)
            worker.close();assertTrue(worker.isStopped);assertFalse(worker.drained.toCompletableFuture().isDone)
            assertFailsWith<ExecutionException>{queued.await()};assertEquals(0,journal.transitions.get())
            journal.release.countDown();assertSame(record,running.await());worker.drained.await();assertEquals(1,journal.calls.get());assertEquals(0,journal.transitions.get())
        } finally {finish(worker,journal)}
    }
    @Test fun startedCommitIsNotInterruptedOrPretendedCancelled() {
        val record=record();val journal=Journal(record).apply {blockTransition=true};val worker=ItemRollbackJournalWorker(journal)
        try {
            val commit=worker.complete(record);assertTrue(journal.entered.await(5,TimeUnit.SECONDS));worker.close();assertFalse(worker.drained.toCompletableFuture().isDone)
            journal.release.countDown();assertTrue(commit.await());worker.drained.await();assertEquals(ItemRollbackPhase.COMPLETED,journal.value.phase);assertEquals(1,journal.transitions.get())
        } finally {finish(worker,journal)}
    }
    @Test fun capacityRejectionNeverRunsJournalOnCaller() {
        val record=record();val journal=Journal(record).apply {blockRead=true};val worker=ItemRollbackJournalWorker(journal)
        try {
            worker.read(record.operationId);assertTrue(journal.entered.await(5,TimeUnit.SECONDS))
            val queued=(1..32).map {worker.read(record.operationId)}
            assertFailsWith<ExecutionException>{worker.read(record.operationId).await()};assertEquals(1,journal.calls.get())
            worker.close();queued.forEach { assertFailsWith<ExecutionException>{it.await()} };assertEquals(1,journal.calls.get())
        } finally {finish(worker,journal)}
    }
    @Test fun closeCallbacksCannotEnqueueNewWork() {
        val record=record();val journal=Journal(record).apply {blockRead=true};val worker=ItemRollbackJournalWorker(journal)
        try {
            worker.read(record.operationId);assertTrue(journal.entered.await(5,TimeUnit.SECONDS));var callbacks=0;var closedDuringCallback=false;var reentrantRejected=false
            worker.complete(record).whenComplete { _: Boolean?, _: Throwable? ->
                callbacks++;closedDuringCallback=worker.isStopped
                try {worker.markApplying(record.operationId).await()} catch (_: ExecutionException) {reentrantRejected=true}
                worker.close()
            }
            worker.close();assertEquals(1,callbacks);assertTrue(closedDuringCallback);assertTrue(reentrantRejected);assertEquals(0,journal.transitions.get())
        } finally {finish(worker,journal)}
    }
    @Test fun callerCancellationCannotHideRunningCommitOrDrain() {
        val record=record();val journal=Journal(record).apply {blockTransition=true};val worker=ItemRollbackJournalWorker(journal)
        try {
            val result=worker.complete(record);assertTrue(journal.entered.await(5,TimeUnit.SECONDS));assertTrue(result.toCompletableFuture().cancel(false))
            worker.close();worker.drained.toCompletableFuture().cancel(false);assertFalse(worker.drained.toCompletableFuture().isDone)
            journal.release.countDown();assertTrue(result.await());worker.drained.await();assertEquals(1,journal.transitions.get())
        } finally {finish(worker,journal)}
    }
    @Test fun failedStartedOperationStillDrainsAndDoesNotRetry() {
        val record=record();val journal=Journal(record).apply {blockTransition=true;fail=true};val worker=ItemRollbackJournalWorker(journal)
        try {
            // Allow record read, then fail only the blocked transition.
            journal.fail=false;val commit=worker.complete(record);assertTrue(journal.entered.await(5,TimeUnit.SECONDS));journal.fail=true
            worker.close();journal.release.countDown();assertFailsWith<ExecutionException>{commit.await()};worker.drained.await();assertEquals(1,journal.transitions.get());assertEquals(record,journal.value)
        } finally {finish(worker,journal)}
    }
    @Test fun changedFullRecordAndInvalidPlansNeverTransition() {
        val record=record();val journal=Journal(ItemRollbackRecord(record.operationId,2,record.phase,record.entries));val worker=ItemRollbackJournalWorker(journal)
        try {assertFalse(worker.complete(record).await());assertFailsWith<IllegalArgumentException>{worker.complete(record(ItemRollbackPhase.PREPARED))};assertFailsWith<IllegalArgumentException>{worker.complete(ItemRollbackRecord(record.operationId,1,record.phase,emptyList()))};assertEquals(0,journal.transitions.get())} finally {finish(worker,journal)}
    }
    @Test fun foreignDriverCannotSubmitCloseOrObserveDrain() {
        val record=record();val journal=Journal(record);val worker=ItemRollbackJournalWorker(journal)
        try {CompletableFuture.runAsync {assertFailsWith<IllegalStateException>{worker.read(record.operationId)};assertFailsWith<IllegalStateException>{worker.markApplying(record.operationId)};assertFailsWith<IllegalStateException>{worker.complete(record)};assertFailsWith<IllegalStateException>{worker.close()};assertFailsWith<IllegalStateException>{worker.drained}}.get(5,TimeUnit.SECONDS);assertEquals(0,journal.calls.get())} finally {finish(worker,journal)}
    }
    @Test fun closingIdleWorkerIsIdempotentAndAllNewRequestsFail() {
        val record=record();val journal=Journal(record);val worker=ItemRollbackJournalWorker(journal)
        worker.close();worker.close();worker.drained.await();assertTrue(worker.isStopped)
        assertFailsWith<ExecutionException>{worker.read(record.operationId).await()};assertFailsWith<ExecutionException>{worker.markApplying(record.operationId).await()};assertFailsWith<ExecutionException>{worker.complete(record).await()};assertEquals(0,journal.calls.get())
    }
}
