package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.ContainerTransactionSnapshot
import java.util.UUID
import java.util.concurrent.*
import kotlin.test.*

class AsyncItemApplyJournalTest {
    private class Journal : ItemRollbackJournal {
        val caller = Thread.currentThread()
        var calls = 0
        override fun itemRollback(operationId: UUID): ItemRollbackRecord? {check(Thread.currentThread()!==caller);calls++;return null}
        override fun transitionItemRollback(operationId: UUID, expected: ItemRollbackPhase,next: ItemRollbackPhase): Boolean {
            check(Thread.currentThread()!==caller);assertEquals(ItemRollbackPhase.PREPARED,expected);assertEquals(ItemRollbackPhase.APPLYING,next);calls++;return true
        }
        override fun prepareItemRollback(operationId: UUID,createdAt: Long,newestFirst: List<ContainerTransactionSnapshot>): ItemRollbackRecord=error("No prepare")
        override fun unfinishedItemRollbacks(limit: Int): List<ItemRollbackSummary> = error("No list")
    }
    @Test fun adapterRunsReadsAndIntentOnWorker() {
        val executor=Executors.newSingleThreadExecutor();val journal=Journal();val port=AsyncItemApplyJournal(journal,executor)
        try {assertNull(port.read(UUID.randomUUID()).toCompletableFuture().get(5,TimeUnit.SECONDS));assertTrue(port.markApplying(UUID.randomUUID()).toCompletableFuture().get(5,TimeUnit.SECONDS));assertEquals(2,journal.calls)} finally {executor.shutdownNow()}
    }
    @Test fun inlineExecutorCannotTouchJournal() {
        val journal=Journal();val port=AsyncItemApplyJournal(journal,Executor {it.run()})
        assertFailsWith<CompletionException>{port.read(UUID.randomUUID()).toCompletableFuture().join()};assertFailsWith<CompletionException>{port.markApplying(UUID.randomUUID()).toCompletableFuture().join()};assertEquals(0,journal.calls)
    }
    @Test fun rejectedExecutorReturnsFailedStageWithoutJournalAccess() {
        val journal=Journal();val port=AsyncItemApplyJournal(journal,Executor {throw RejectedExecutionException("Queue full")})
        assertFailsWith<CompletionException>{port.read(UUID.randomUUID()).toCompletableFuture().join()};assertEquals(0,journal.calls)
    }
    @Test fun otherDriverThreadCannotSubmitJournalWork() {
        val journal=Journal();val port=AsyncItemApplyJournal(journal,Executor {error("Must not submit")})
        CompletableFuture.runAsync {assertFailsWith<IllegalStateException>{port.read(UUID.randomUUID())};assertFailsWith<IllegalStateException>{port.markApplying(UUID.randomUUID())}}.get(5,TimeUnit.SECONDS);assertEquals(0,journal.calls)
    }
}
