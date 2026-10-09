package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import java.util.concurrent.*
import kotlin.test.*

class ItemJournalRetentionTest {
    private val dimension=ResourceId.parse("minecraft:overworld")
    private val a=ItemSlotOwner.BlockContainer(dimension,BlockPosition(1,64,1))
    private val b=ItemSlotOwner.PlayerInventory(UUID.randomUUID())
    private class Journal : ItemRollbackJournal {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        override fun itemRollback(operationId: UUID): ItemRollbackRecord? {entered.countDown();check(release.await(5,TimeUnit.SECONDS));return null}
        override fun transitionItemRollback(operationId: UUID,expected: ItemRollbackPhase,next: ItemRollbackPhase)=false
        override fun prepareItemRollback(operationId: UUID,createdAt: Long,newestFirst: List<ContainerTransactionSnapshot>): ItemRollbackRecord=error("No prepare")
        override fun unfinishedItemRollbacks(limit: Int): List<ItemRollbackSummary> = error("No list")
    }
    private fun drain(worker: ItemRollbackJournalWorker,journal: Journal) {worker.close();journal.release.countDown();worker.drained.toCompletableFuture().get(5,TimeUnit.SECONDS)}
    @Test fun actualLegacyDrainDoesNotWaitForAnUnrelatedObserverToPropagate() {
        val gate=ItemOwnerCoordination();val journal=Journal();val worker=ItemRollbackJournalWorker(journal)
        val observed=CountDownLatch(1);val unblock=CountDownLatch(1)
        try {
            val lease=assertNotNull(gate.acquire(UUID.randomUUID(),listOf(a)));lease.retainUntilJournalDrained(worker)
            worker.read(lease.operationId);assertTrue(journal.entered.await(5,TimeUnit.SECONDS));lease.close()
            // Registered after the retained projection: deliberately block completion propagation.
            worker.drained.thenRun {observed.countDown();check(unblock.await(5,TimeUnit.SECONDS))}
            worker.close();journal.release.countDown();assertTrue(observed.await(5,TimeUnit.SECONDS))
            assertTrue(worker.isActuallyDrained)
            assertFalse(gate.hasReservations());assertTrue(gate.allowsMutation(a))
        } finally {unblock.countDown();drain(worker,journal)}
    }

    @Test fun journalRetentionPausesUnrelatedTransfersAndMenusBeforeResolution() {
        val gate=ItemOwnerCoordination();val journal=Journal();val worker=ItemRollbackJournalWorker(journal)
        try {
            val lease=assertNotNull(gate.acquire(UUID.randomUUID(),listOf(a)));lease.retainUntilJournalDrained(worker)
            val transfers=ItemTransferCoordination(gate);val menus=ItemMenuCoordination(gate);var resolved=0
            val other=ItemSlotOwner.BlockContainer(dimension,BlockPosition(2,64,1));val player=UUID.randomUUID()
            assertFalse(transfers.allowsExternalTransfer {resolved++;listOf(other)});assertFalse(menus.allowsMutation(player) {resolved++;listOf(other)});assertEquals(0,resolved)
            lease.close();drain(worker,journal);assertTrue(transfers.allowsExternalTransfer {resolved++;listOf(other)});assertTrue(menus.allowsMutation(player) {resolved++;listOf(other)});assertEquals(0,resolved)
        } finally {drain(worker,journal)}
    }
    @Test fun retentionQueryIncludesRevokedEntriesAndStopsOnlyAfterDrain() {
        val gate=ItemOwnerCoordination();val journal=Journal();val worker=ItemRollbackJournalWorker(journal)
        try {
            assertFalse(gate.hasJournalRetention())
            val lease=assertNotNull(gate.acquire(UUID.randomUUID(),listOf(a)));lease.retainUntilJournalDrained(worker)
            assertTrue(gate.hasJournalRetention());lease.close();assertTrue(gate.hasJournalRetention());gate.stop();assertTrue(gate.hasJournalRetention())
            drain(worker,journal);assertFalse(gate.hasJournalRetention())
        } finally {drain(worker,journal)}
    }
    @Test fun expiryKeepsOwnersReservedAndRejectsExpiredPermitUntilDrain() {
        var now=0L;val gate=ItemOwnerCoordination {now};val journal=Journal();val worker=ItemRollbackJournalWorker(journal)
        try {
            val lease=assertNotNull(gate.acquire(UUID.randomUUID(),listOf(a,b)));lease.retainUntilJournalDrained(worker)
            worker.read(lease.operationId);assertTrue(journal.entered.await(5,TimeUnit.SECONDS));now=TimeUnit.SECONDS.toNanos(10)
            assertEquals(ItemOwnerLeaseState.EXPIRED,lease.state);assertFalse(lease.isCurrent(lease.owners));assertTrue(gate.hasReservations())
            listOf(a,b).forEach {assertFalse(gate.allowsMutation(it));assertFalse(gate.allowsMutation(it,lease));assertNull(gate.acquire(UUID.randomUUID(),listOf(it)))}
            worker.close();assertTrue(gate.hasReservations());drain(worker,journal);assertFalse(gate.hasReservations());assertTrue(gate.allowsMutation(a));assertNotNull(gate.acquire(UUID.randomUUID(),listOf(a,b)))
        } finally {drain(worker,journal)}
    }
    @Test fun closeInvalidationAndChunkInvalidationRetainWholeOperation() {
        for(kind in 0..3) {
            val gate=ItemOwnerCoordination();val journal=Journal();val worker=ItemRollbackJournalWorker(journal)
            try {
                val lease=assertNotNull(gate.acquire(UUID.randomUUID(),listOf(a,b)));lease.retainUntilJournalDrained(worker)
                when(kind) {0->lease.close();1->gate.invalidate(a);2->gate.invalidateAll();else->gate.invalidateBlockChunk(dimension,0,0)}
                assertEquals(if(kind==0)ItemOwnerLeaseState.RELEASED else ItemOwnerLeaseState.INVALIDATED,lease.state)
                assertTrue(gate.hasReservations());assertFalse(lease.isCurrent(lease.owners))
                listOf(a,b).forEach {assertFalse(gate.allowsMutation(it));assertFalse(gate.allowsMutation(it,lease));assertNull(gate.acquire(UUID.randomUUID(),listOf(it)))}
                lease.close();assertTrue(gate.hasReservations());drain(worker,journal);assertFalse(gate.hasReservations());assertTrue(gate.allowsMutation(a))
            } finally {drain(worker,journal)}
        }
    }
    @Test fun stoppedRegistryRetainsPendingEntriesButNeverPermitsMutations() {
        val gate=ItemOwnerCoordination();val journal=Journal();val worker=ItemRollbackJournalWorker(journal)
        try {
            val lease=assertNotNull(gate.acquire(UUID.randomUUID(),listOf(a,b)));lease.retainUntilJournalDrained(worker);gate.stop();gate.stop()
            assertEquals(ItemOwnerLeaseState.STOPPED,lease.state);assertTrue(gate.hasReservations());assertFalse(gate.isRunning());assertFalse(gate.allowsMutation(a));assertFalse(gate.allowsMutation(a,lease));assertNull(gate.acquire(UUID.randomUUID(),listOf(a)))
            drain(worker,journal);assertFalse(gate.hasReservations());assertFalse(gate.allowsMutation(a));assertEquals(ItemOwnerLeaseState.STOPPED,lease.state)
        } finally {drain(worker,journal)}
    }
    @Test fun completionCallbacksCannotReleaseRegistryEntriesOnWorkerThread() {
        val gate=ItemOwnerCoordination();val journal=Journal();val worker=ItemRollbackJournalWorker(journal)
        try {
            val lease=assertNotNull(gate.acquire(UUID.randomUUID(),listOf(a)));lease.retainUntilJournalDrained(worker);worker.read(lease.operationId);assertTrue(journal.entered.await(5,TimeUnit.SECONDS));lease.close()
            val observed=worker.drained.thenApply {runCatching {gate.hasReservations()}.exceptionOrNull()}
            worker.close();journal.release.countDown();assertIs<IllegalStateException>(observed.toCompletableFuture().get(5,TimeUnit.SECONDS));assertFalse(gate.hasReservations())
        } finally {drain(worker,journal)}
    }
    @Test fun retentionDoesNotMakeForeignExpiredReleasedOrStoppedLeaseCurrent() {
        var now=0L;val gate=ItemOwnerCoordination {now};val journal=Journal();val worker=ItemRollbackJournalWorker(journal)
        try {
            val released=assertNotNull(gate.acquire(UUID.randomUUID(),listOf(a)));released.close();assertFailsWith<IllegalStateException>{released.retainUntilJournalDrained(worker)}
            val expired=assertNotNull(gate.acquire(UUID.randomUUID(),listOf(a)));now=TimeUnit.SECONDS.toNanos(10);assertFailsWith<IllegalStateException>{expired.retainUntilJournalDrained(worker)}
            val active=assertNotNull(gate.acquire(UUID.randomUUID(),listOf(a)));worker.close();assertFailsWith<IllegalStateException>{active.retainUntilJournalDrained(worker)};active.close()
        } finally {drain(worker,journal)}
    }
    @Test fun duplicateRetentionAndForeignThreadRegistrationAreRefused() {
        val gate=ItemOwnerCoordination();val journal=Journal();val worker=ItemRollbackJournalWorker(journal)
        try {
            val lease=assertNotNull(gate.acquire(UUID.randomUUID(),listOf(a)));lease.retainUntilJournalDrained(worker);assertFailsWith<IllegalStateException>{lease.retainUntilJournalDrained(worker)}
            CompletableFuture.runAsync {assertFailsWith<IllegalStateException>{lease.retainUntilJournalDrained(worker)}}.get(5,TimeUnit.SECONDS)
            val executor=Executors.newSingleThreadExecutor()
            try {
                val foreign=executor.submit<ItemRollbackJournalWorker> {ItemRollbackJournalWorker(journal)}.get(5,TimeUnit.SECONDS)
                try {
                    val second=assertNotNull(gate.acquire(UUID.randomUUID(),listOf(b)));assertFailsWith<IllegalStateException>{second.retainUntilJournalDrained(foreign)}
                } finally {executor.submit {foreign.close()}.get(5,TimeUnit.SECONDS)}
            } finally {executor.shutdownNow()}
        } finally {drain(worker,journal)}
    }
    @Test fun drainBeforeDeadlinePreservesActiveLeaseUntilNormalClose() {
        val gate=ItemOwnerCoordination();val journal=Journal();val worker=ItemRollbackJournalWorker(journal)
        try {val lease=assertNotNull(gate.acquire(UUID.randomUUID(),listOf(a)));lease.retainUntilJournalDrained(worker);drain(worker,journal);assertTrue(lease.isCurrent(lease.owners));assertTrue(gate.allowsMutation(a,lease));lease.close();assertFalse(gate.hasReservations())} finally {drain(worker,journal)}
    }
    @Test fun retainedOperationsStillConsumeTheBoundedRegistryCapacity() {
        val gate=ItemOwnerCoordination();val journal=Journal();val worker=ItemRollbackJournalWorker(journal)
        try {
            (1..32).forEach {x->assertNotNull(gate.acquire(UUID.randomUUID(),listOf(ItemSlotOwner.BlockContainer(dimension,BlockPosition(x,64,1))))).also {it.retainUntilJournalDrained(worker);it.close()}}
            assertNull(gate.acquire(UUID.randomUUID(),listOf(b)));drain(worker,journal);assertFalse(gate.hasReservations());assertNotNull(gate.acquire(UUID.randomUUID(),listOf(b)))
        } finally {drain(worker,journal)}
    }
}
