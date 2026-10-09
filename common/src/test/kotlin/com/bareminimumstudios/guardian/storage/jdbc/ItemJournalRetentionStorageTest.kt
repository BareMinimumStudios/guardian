package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.*
import kotlin.test.*

/** Real SQLite commit drain with retained reservations, not complete Minecraft mutation exclusion. */
class ItemJournalRetentionStorageTest {
    private val dimension=ResourceId.parse("minecraft:overworld")
    private val a=ItemSlotAddress(ItemSlotOwner.BlockContainer(dimension,BlockPosition(1,64,1)),0)
    private val b=ItemSlotAddress(ItemSlotOwner.BlockContainer(dimension,BlockPosition(2,64,1)),0)
    private val item=ItemStackSnapshot(ResourceId.parse("minecraft:coal"),1,BinaryPayload.of(byteArrayOf(1)))
    private fun prepare(db: SqliteStorageBackend): ItemRollbackRecord {
        db.open()
        val row=ContainerTransactionSnapshot(UUID.randomUUID(),1,ActorIdentity.System("hopper"),0,ContainerAction.HOPPER_TRANSFER,listOf(ItemSlotChange(a,item,ItemStackSnapshot.EMPTY),ItemSlotChange(b,ItemStackSnapshot.EMPTY,item)))
        db.append(listOf(ContainerAuditEntry(row)));val id=UUID.randomUUID();db.prepareItemRollback(id,2,listOf(row));assertTrue(db.transitionItemRollback(id,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING));return db.itemRollback(id)!!
    }
    private fun <T> CompletionStage<T>.await(): T=toCompletableFuture().get(5,TimeUnit.SECONDS)
    private fun assertClaims(path: Path,owners: Int) {
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection -> connection.createStatement().use { query ->
            query.executeQuery("select count(*) from ex_item_rollback_owner").use {assertTrue(it.next());assertEquals(owners,it.getInt(1))}
            query.executeQuery("select count(*) from ex_item_rollback_claim").use {assertTrue(it.next());assertEquals(1,it.getInt(1))}
        } }
    }
    @Test fun runningSqliteCommitRetainsExpiredOwnersUntilActualDrain() {
        for(lostAcknowledgement in listOf(false,true)) {
            val path=Files.createTempDirectory("guardian-worker-running").resolve("audit.sqlite");var id: UUID?=null
            SqliteStorageBackend(path).use { db ->
                val record=prepare(db);id=record.operationId;val entered=CountDownLatch(1);val release=CountDownLatch(1)
                val journal=object : ItemRollbackJournal by db {
                    override fun transitionItemRollback(operationId: UUID,expected: ItemRollbackPhase,next: ItemRollbackPhase): Boolean {
                        entered.countDown();check(release.await(5,TimeUnit.SECONDS))
                        val result=db.transitionItemRollback(operationId,expected,next)
                        if(lostAcknowledgement) error("Acknowledgement lost after SQLite commit")
                        return result
                    }
                }
                var now=0L;val gate=ItemOwnerCoordination {now};val worker=ItemRollbackJournalWorker(journal)
                val lease=assertNotNull(gate.acquire(record.operationId,listOf(a.owner,b.owner)));lease.retainUntilJournalDrained(worker)
                try {
                    val completion=worker.complete(record);assertTrue(entered.await(5,TimeUnit.SECONDS));now=TimeUnit.SECONDS.toNanos(10);assertEquals(ItemOwnerLeaseState.EXPIRED,lease.state)
                    worker.close();assertFalse(worker.drained.toCompletableFuture().isDone)
                    assertTrue(gate.hasReservations());assertNull(gate.acquire(UUID.randomUUID(),listOf(a.owner,b.owner)));assertFalse(gate.allowsMutation(a.owner,lease))
                    release.countDown();if(lostAcknowledgement) assertFailsWith<ExecutionException>{completion.await()} else assertTrue(completion.await())
                    worker.drained.await();assertEquals(ItemRollbackPhase.COMPLETED,db.itemRollback(record.operationId)?.phase)
                    assertFalse(gate.hasReservations());assertNotNull(gate.acquire(UUID.randomUUID(),listOf(a.owner,b.owner)))
                } finally {worker.close();release.countDown();worker.drained.await()}
            }
            SqliteStorageBackend(path).use {db ->db.open();assertEquals(ItemRollbackPhase.COMPLETED,db.itemRollback(id!!)?.phase);assertEquals(0,db.health().unfinishedItemRollbacks)}
            assertClaims(path,0)
        }
    }
}
