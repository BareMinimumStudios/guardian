package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ItemOperationScopeTest {
    private val main = Thread.currentThread()
    private val calls = AtomicInteger()
    private val closed = CountDownLatch(1)
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    @Volatile private var block = false
    @Volatile private var blockCommit = false
    @Volatile private var closeThread: Thread? = null
    private val values = ConcurrentHashMap<UUID, ItemRollbackRecord>()
    private val item = ItemStackSnapshot(ResourceId.parse("minecraft:coal"),1,BinaryPayload.of(byteArrayOf(1)))
    private val coordination = ItemOwnerCoordination()
    private val journal = object : ItemRollbackJournal {
        override fun itemRollback(operationId: UUID): ItemRollbackRecord? {
            check(Thread.currentThread() !== main && closed.count == 1L)
            if (block) { entered.countDown();check(release.await(5,TimeUnit.SECONDS)) }
            check(closed.count == 1L)
            return values[operationId]
        }
        override fun transitionItemRollback(operationId: UUID,expected: ItemRollbackPhase,next: ItemRollbackPhase): Boolean {
            check(Thread.currentThread() !== main && closed.count == 1L)
            if (blockCommit && next == ItemRollbackPhase.COMPLETED) {
                entered.countDown();check(release.await(5,TimeUnit.SECONDS))
            }
            check(closed.count == 1L)
            val old=checkNotNull(values[operationId]);if(old.phase != expected)return false
            values[operationId]=ItemRollbackRecord(old.operationId,old.createdAt,next,old.entries)
            return true
        }
        override fun prepareItemRollback(operationId: UUID,createdAt: Long,newestFirst: List<ContainerTransactionSnapshot>): ItemRollbackRecord = error("No prepare")
        override fun unfinishedItemRollbacks(limit: Int): List<ItemRollbackSummary> = error("No list")
    }
    private fun record(): ItemRollbackRecord {
        val a=ItemSlotAddress(ItemSlotOwner.PlayerInventory(UUID.randomUUID()),0)
        val b=ItemSlotAddress(ItemSlotOwner.PlayerInventory(UUID.randomUUID()),0)
        return ItemRollbackRecord(UUID.randomUUID(),1,ItemRollbackPhase.PREPARED,listOf(ItemRollbackEntry(UUID.randomUUID(),listOf(
            ItemSlotChange(a,item,ItemStackSnapshot.EMPTY),ItemSlotChange(b,ItemStackSnapshot.EMPTY,item))))).also { values[it.operationId]=it }
    }
    private inner class Port(record: ItemRollbackRecord) : ItemOperationPort {
        val slots=record.entries.flatMap { it.changes }.associate { it.address to it.after }.toMutableMap()
        var onClose: () -> Unit = {}
        var writes=0
        var onWrite: () -> Unit = {}
        var pending: CompletableFuture<InventorySnapshot?>?=null
        override fun isExclusiveAndCurrent(owners: Set<ItemSlotOwner>) = true
        override fun readOwners(owners: Set<ItemSlotOwner>) = InventorySnapshot(slots)
        override fun writeSlot(address: ItemSlotAddress,expected: ItemStackSnapshot,replacement: ItemStackSnapshot) { assertEquals(expected,slots[address]);slots[address]=replacement;writes++;onWrite() }
        override fun saveAndReadBack(owner: ItemSlotOwner,addresses: Set<ItemSlotAddress>): CompletionStage<InventorySnapshot?> =
            pending ?: CompletableFuture.completedFuture(InventorySnapshot(slots.filterKeys { it.owner == owner }))
        override fun close() { onClose() }
    }
    private fun scope(onClose: () -> Unit = {}) = ItemOperationScope(journal) {
        closeThread=Thread.currentThread();calls.incrementAndGet();onClose();closed.countDown()
    }
    private fun finish(scope: ItemOperationScope) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(scope.state !in setOf(ItemOperationScopeState.CLOSED,ItemOperationScopeState.FAILED)) {
            check(System.nanoTime()<deadline);scope.advance();Thread.sleep(1)
        }
    }
    private fun until(host: ItemOperationHost,condition: () -> Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!condition()) { check(System.nanoTime()<deadline);host.advance();Thread.sleep(1) }
    }
    @Test fun emptyScopeClosesBackendOffThreadExactlyOnce() {
        val scope=scope();assertEquals(ItemOperationScopeState.OPEN,scope.advance())
        scope.close();scope.close();finish(scope);scope.close();scope.advance()
        assertEquals(1,calls.get());assertNotSame(main,closeThread);assertEquals(ItemOperationScopeState.CLOSED,scope.state)
    }
    @Test fun startedReadMustDrainBeforeBackendClosure() {
        block=true;val scope=scope();val record=record();val port=Port(record)
        val host=assertNotNull(scope.start(record,coordination,{port}));host.advance()
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));scope.close();repeat(5){scope.advance()}
            assertEquals(0,calls.get());assertEquals(ItemOperationScopeState.DRAINING,scope.state)
            assertTrue(coordination.hasJournalRetention());assertEquals(0,port.writes)
        } finally { release.countDown();scope.close();finish(scope) }
        assertEquals(ItemOperationState.RECOVERY_REQUIRED,host.state);assertTrue(coordination.hasJournalRetention())
        assertEquals(1,calls.get());assertEquals(ItemRollbackPhase.PREPARED,values[record.operationId]?.phase)
    }
    @Test fun lateCompletedCommitRemainsProtectedAfterBackendClosure() {
        blockCommit=true;val scope=scope();val record=record();val port=Port(record)
        val host=assertNotNull(scope.start(record,coordination,{port}))
        try {
            until(host){entered.count==0L};scope.close();scope.advance();assertEquals(0,calls.get())
        } finally {release.countDown();scope.close();finish(scope)}
        assertEquals(ItemRollbackPhase.COMPLETED,values[record.operationId]?.phase)
        assertEquals(ItemOperationState.RECOVERY_REQUIRED,host.state);assertTrue(coordination.hasJournalRetention())
        assertEquals(2,port.writes);assertEquals(1,calls.get())
    }
    @Test fun multipleHostsAllDrainBeforeSharedBackendCloses() {
        block=true;val scope=scope();val first=record();val second=record()
        val a=assertNotNull(scope.start(first,coordination,{Port(first)}))
        val b=assertNotNull(scope.start(second,coordination,{Port(second)}))
        a.advance();b.advance()
        try { assertTrue(entered.await(5,TimeUnit.SECONDS));scope.close();scope.advance();assertEquals(0,calls.get()) }
        finally {release.countDown();scope.close();finish(scope)}
        assertTrue(a.isJournalDrained && b.isJournalDrained);assertEquals(1,calls.get());assertTrue(coordination.hasJournalRetention())
    }
    @Test fun closeCallbackCannotAdmitAnotherOperation() {
        val scope=scope();val record=record();val port=Port(record)
        scope.start(record,coordination,{port});var refused=false
        port.onClose={assertFailsWith<IllegalStateException>{scope.start(record(),coordination,{error("Unexpected bind")})};refused=true}
        scope.close();finish(scope);assertTrue(refused);assertEquals(1,calls.get())
    }
    @Test fun closeDuringBindingStopsReturnedHostBeforeAnyRequest() {
        val scope=scope();val record=record();val port=Port(record)
        val host=assertNotNull(scope.start(record,coordination,{
            scope.close();assertFailsWith<IllegalStateException>{scope.advance()};port
        }))
        assertEquals(ItemOperationState.DRAINING,host.state);finish(scope)
        assertEquals(0,port.writes);assertEquals(ItemRollbackPhase.PREPARED,values[record.operationId]?.phase)
    }
    @Test fun observerCancellationCannotMaskBackendFailureOrRetryClosure() {
        val closeEntered=CountDownLatch(1);val closeRelease=CountDownLatch(1)
        val scope=scope {closeEntered.countDown();check(closeRelease.await(5,TimeUnit.SECONDS));error("Closure failed")}
        scope.close();scope.advance()
        try {
            assertTrue(closeEntered.await(5,TimeUnit.SECONDS));assertTrue(scope.shutdown.toCompletableFuture().cancel(false))
            assertEquals(ItemOperationScopeState.CLOSING_BACKEND,scope.advance())
        } finally {closeRelease.countDown();finish(scope)}
        assertEquals(ItemOperationScopeState.FAILED,scope.state);assertEquals("Closure failed",scope.failure?.message)
        scope.close();scope.advance();assertEquals(1,calls.get())
        assertFailsWith<ExecutionException>{scope.shutdown.toCompletableFuture().get()}
    }
    @Test fun pendingDiskSaveDoesNotSubmitJournalWorkAfterStop() {
        val scope=scope();val record=record();val port=Port(record).apply {pending=CompletableFuture()}
        val host=assertNotNull(scope.start(record,coordination,{port}))
        until(host){host.state==ItemOperationState.SAVING};host.advance();host.advance()
        scope.close();finish(scope);assertFalse(port.pending!!.isDone)
        port.pending!!.complete(InventorySnapshot(port.slots));host.advance();scope.advance()
        assertEquals(ItemRollbackPhase.APPLYING,values[record.operationId]?.phase);assertTrue(coordination.hasJournalRetention())
        assertEquals(1,calls.get())
    }
    @Test fun controlsRejectOtherThreads() {
        val scope=scope();val pool=Executors.newSingleThreadExecutor()
        try { pool.submit { assertFailsWith<IllegalStateException>{scope.close()};assertFailsWith<IllegalStateException>{scope.advance()};assertFailsWith<IllegalStateException>{scope.shutdown} }.get(5,TimeUnit.SECONDS) }
        finally {pool.shutdownNow();scope.close();finish(scope)}
    }
    @Test fun openScopeTicksItsHostsWithoutIndividualPolling() {
        val scope=scope();val record=record();val port=Port(record)
        val host=assertNotNull(scope.start(record,coordination,{port}))
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(host.state!=ItemOperationState.COMPLETED) {
            check(System.nanoTime()<deadline);assertEquals(ItemOperationScopeState.OPEN,scope.advance());Thread.sleep(1)
        }
        assertEquals(2,port.writes);assertEquals(0,calls.get())
        assertFalse(coordination.hasJournalRetention())
        scope.close();finish(scope);assertEquals(1,calls.get())
    }
    @Test fun scopeCancellationStopsAnOwnedHostWithoutClosingAdmission() {
        val scope=scope();val record=record();val port=Port(record)
        val host=assertNotNull(scope.start(record,coordination,{port}))
        scope.cancel(host);scope.advance()
        assertEquals(ItemOperationState.RECOVERY_REQUIRED,host.state)
        assertEquals(ItemOperationScopeState.OPEN,scope.state);assertEquals(0,port.writes)
        assertTrue(coordination.hasJournalRetention());scope.close();finish(scope)
    }
    @Test fun scopeRefusesCancellationOfAnotherScopesHost() {
        val first=scope();val second=scope();val record=record()
        val host=assertNotNull(first.start(record,coordination,{Port(record)}))
        assertFailsWith<IllegalStateException>{second.cancel(host)}
        first.close();finish(first);second.close();finish(second)
    }
    @Test fun actualDiskDrainMustFinishBeforeBackendClosure() {
        val scope=scope();val disk=CompletableFuture<Void>();scope.awaitDiskDrain(disk.minimalCompletionStage())
        scope.close();repeat(5){scope.advance()}
        assertEquals(ItemOperationScopeState.DRAINING,scope.state);assertEquals(0,calls.get())
        scope.shutdown.toCompletableFuture().cancel(false)
        disk.complete(null);finish(scope);assertEquals(ItemOperationScopeState.CLOSED,scope.state);assertEquals(1,calls.get())
    }
    @Test fun diskFailureKeepsBackendOpenAndReportsFailure() {
        val scope=scope();val disk=CompletableFuture<Void>();scope.awaitDiskDrain(disk.minimalCompletionStage())
        scope.close();disk.completeExceptionally(IllegalStateException("Disk writer did not drain"));scope.advance()
        assertEquals(ItemOperationScopeState.FAILED,scope.state);assertEquals(0,calls.get())
        assertEquals("Disk writer did not drain",scope.failure?.message)
        assertFailsWith<ExecutionException>{scope.shutdown.toCompletableFuture().get()}
        scope.close();scope.advance();assertEquals(0,calls.get())
    }
    @Test fun diskRegistrationRejectsReplacementAndLateRegistration() {
        val scope=scope();scope.awaitDiskDrain(CompletableFuture.completedFuture(null))
        assertFailsWith<IllegalStateException>{scope.awaitDiskDrain(CompletableFuture.completedFuture(null))}
        scope.close();assertFailsWith<IllegalStateException>{scope.awaitDiskDrain(CompletableFuture.completedFuture(null))}
        finish(scope)
    }

    @Test fun cancelledDiskDrainIsFailureRatherThanProofOfCompletion() {
        val scope=scope();val disk=CompletableFuture<Void>();scope.awaitDiskDrain(disk.minimalCompletionStage())
        scope.close();disk.cancel(false);scope.advance()
        assertEquals(ItemOperationScopeState.FAILED,scope.state);assertEquals(0,calls.get())
        assertIs<CancellationException>(scope.failure)
    }
    @Test fun closeFromTickCallbackPreventsFurtherWrites() {
        val scope=scope();val record=record();val port=Port(record).apply {onWrite={scope.close()}}
        val host=assertNotNull(scope.start(record,coordination,{port}))
        finish(scope)
        assertEquals(1,port.writes);assertEquals(ItemOperationState.RECOVERY_REQUIRED,host.state)
        assertTrue(coordination.hasJournalRetention());assertEquals(1,calls.get())
    }
    @Test fun tickCallbacksCannotReenterPollingOrAdmission() {
        val scope=scope();val record=record();var refused=false
        val port=Port(record).apply {onWrite={
            assertFailsWith<IllegalStateException>{scope.advance()}
            assertFailsWith<IllegalStateException>{scope.start(record(),coordination,{error("Unexpected bind")})}
            refused=true
        }}
        val host=assertNotNull(scope.start(record,coordination,{port}))
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(host.state!=ItemOperationState.COMPLETED){check(System.nanoTime()<deadline);scope.advance();Thread.sleep(1)}
        assertTrue(refused);assertEquals(2,port.writes);scope.close();finish(scope)
    }

}
