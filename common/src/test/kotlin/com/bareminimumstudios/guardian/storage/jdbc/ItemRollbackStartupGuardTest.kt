package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.rollback.*
import java.util.UUID
import kotlin.test.*

class ItemRollbackStartupGuardTest {
    private class Journal(var phase: ItemRollbackPhase?) : ItemRollbackJournal {
        val id=UUID.randomUUID()
        override fun unfinishedItemRollbacks(limit: Int) = phase?.let { listOf(ItemRollbackSummary(id,1,it)) } ?: emptyList()
        override fun itemRollback(operationId: UUID): ItemRollbackRecord? = error("Startup does not load item payloads")
        override fun prepareItemRollback(operationId: UUID, createdAt: Long, newestFirst: List<com.bareminimumstudios.guardian.domain.ContainerTransactionSnapshot>): ItemRollbackRecord = error("No new admission")
        override fun transitionItemRollback(operationId: UUID, expected: ItemRollbackPhase, next: ItemRollbackPhase): Boolean = error("Unknown capability cannot cancel")
    }
    private class Protection(private val delegate: Journal, private val held: Boolean) : ItemRollbackProtectionJournal, ItemRollbackJournal by delegate {
        override fun itemRollbackProtected(operationId: UUID)=held
        override fun protectItemRollback(record: ItemRollbackRecord): Boolean=error("No new protection")
        override fun protectItemRollback(record: ItemRollbackRecord, original: com.bareminimumstudios.guardian.domain.InventorySnapshot): Boolean=error("No new protection")
        override fun itemRollbackImages(operationId: UUID): ItemRollbackImageRecord?=error("No payload reads")
        override fun acknowledgeItemRollback(record: ItemRollbackRecord, images: ItemRollbackImages, saved: com.bareminimumstudios.guardian.domain.InventorySnapshot): Boolean=error("No acknowledgment")
        override fun transitionItemRollback(operationId: UUID, expected: ItemRollbackPhase, next: ItemRollbackPhase): Boolean {
            check(operationId==delegate.id && expected==ItemRollbackPhase.PREPARED && next==ItemRollbackPhase.CANCELLED && !held)
            delegate.phase=null;return true
        }
    }
    @Test fun unprotectedUnstartedAdmissionCanBeCancelledWithoutReadingItemPayloads() {
        val journal=Journal(ItemRollbackPhase.PREPARED)
        ItemRollbackStartupGuard.verify(Protection(journal,false))
        assertNull(journal.phase)
    }
    @Test fun protectedPreparedAdmissionCannotBeDiscardedAtStartup() {
        val journal=Journal(ItemRollbackPhase.PREPARED)
        assertFailsWith<IllegalStateException> { ItemRollbackStartupGuard.verify(Protection(journal,true)) }
        assertEquals(ItemRollbackPhase.PREPARED,journal.phase)
    }
    @Test fun openedStorageIsCheckedBeforeTheWriterStartsAndClosedOnRefusal() {
        val path=java.nio.file.Files.createTempDirectory("guardian-startup-item-check").resolve("history.sqlite")
        val db=SqliteStorageBackend(path)
        val pipeline=com.bareminimumstudios.guardian.logging.BufferedLogPipeline(db,queueCapacity=8,batchSize=4,flushIntervalMillis=10)
        var checked=false
        assertFailsWith<IllegalStateException> {
            pipeline.start {
                assertTrue(db.unfinishedItemRollbacks(1).isEmpty())
                checked=true
                error("Refused before writer startup")
            }
        }
        assertTrue(checked)
        assertTrue(pipeline.drained.toCompletableFuture().isDone)
        SqliteStorageBackend(path).use { reopened -> reopened.open();assertTrue(reopened.unfinishedItemRollbacks(1).isEmpty()) }
    }
    @Test fun cleanJournalAllowsStartup() { ItemRollbackStartupGuard.verify(Journal(null)) }
    @Test fun unknownPreparedCapabilityAndEveryUnresolvedPhaseRefuseGameplay() {
        for(phase in listOf(ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING,ItemRollbackPhase.RECOVERY_REQUIRED,ItemRollbackPhase.COMPLETED)) {
            val journal=Journal(phase)
            val error=assertFailsWith<IllegalStateException> { ItemRollbackStartupGuard.verify(journal) }
            assertTrue(error.message!!.contains(journal.id.toString()))
            assertEquals(phase,journal.phase)
        }
    }
}
