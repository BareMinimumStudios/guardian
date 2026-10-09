package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.*
import kotlin.test.*

/** Real SQLite shutdown/restart checks with controlled inventory saves, not Minecraft acceptance. */
class ItemJournalWorkerStorageTest {
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
    @Test fun queuedCompletionAtShutdownRetainsBothOwnersForRecovery() {
        val path=Files.createTempDirectory("guardian-worker-queued").resolve("audit.sqlite");var id: UUID?=null
        SqliteStorageBackend(path).use { db ->
            val record=prepare(db);id=record.operationId;val entered=CountDownLatch(1);val release=CountDownLatch(1)
            val journal=object : ItemRollbackJournal by db {
                override fun itemRollback(operationId: UUID): ItemRollbackRecord? {entered.countDown();check(release.await(5,TimeUnit.SECONDS));return db.itemRollback(operationId)}
            }
            val worker=ItemRollbackJournalWorker(journal)
            try {
                val read=worker.read(record.operationId);assertTrue(entered.await(5,TimeUnit.SECONDS));val completion=worker.complete(record);worker.close()
                assertFailsWith<ExecutionException>{completion.await()};assertFalse(worker.drained.toCompletableFuture().isDone)
                release.countDown();assertEquals(ItemRollbackPhase.APPLYING,read.await()?.phase);worker.drained.await()
                assertEquals(ItemRollbackPhase.APPLYING,db.itemRollback(record.operationId)?.phase)
            } finally {worker.close();release.countDown();worker.drained.await()}
        }
        SqliteStorageBackend(path).use {db ->db.open();assertEquals(ItemRollbackPhase.RECOVERY_REQUIRED,db.itemRollback(id!!)?.phase)}
        assertClaims(path,2)
    }
    @Test fun runningCompletionDrainsAndPersistentResultSurvivesLostAcknowledgement() {
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
                val worker=ItemRollbackJournalWorker(journal)
                try {
                    val completion=worker.complete(record);assertTrue(entered.await(5,TimeUnit.SECONDS));worker.close();assertFalse(worker.drained.toCompletableFuture().isDone)
                    release.countDown();if(lostAcknowledgement) assertFailsWith<ExecutionException>{completion.await()} else assertTrue(completion.await())
                    worker.drained.await();assertEquals(ItemRollbackPhase.COMPLETED,db.itemRollback(record.operationId)?.phase)
                } finally {worker.close();release.countDown();worker.drained.await()}
            }
            SqliteStorageBackend(path).use {db ->db.open();assertEquals(ItemRollbackPhase.COMPLETED,db.itemRollback(id!!)?.phase);assertEquals(0,db.health().unfinishedItemRollbacks)}
            assertClaims(path,0)
        }
    }
    @Test fun asyncSaveProtocolUsesBoundedWorkerAndSQLiteCompletedReadback() {
        val path=Files.createTempDirectory("guardian-worker-protocol").resolve("audit.sqlite");var id: UUID?=null
        SqliteStorageBackend(path).use { db ->
            val record=prepare(db);id=record.operationId;val thread=Thread.currentThread();var saves=0
            val port=object : ItemSavePort {
                override fun isExclusiveAndCurrent(owners: Set<ItemSlotOwner>): Boolean {assertSame(thread,Thread.currentThread());return true}
                override fun saveAndReadBack(owner: ItemSlotOwner,addresses: Set<ItemSlotAddress>): CompletionStage<InventorySnapshot?> {
                    assertSame(thread,Thread.currentThread());saves++
                    return CompletableFuture.completedFuture(InventorySnapshot(addresses.associateWith {if(it==a)item else ItemStackSnapshot.EMPTY}))
                }
            }
            val worker=ItemRollbackJournalWorker(db)
            try {
                val driver=AsyncItemSaveCompletion(record,worker,port);val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
                while(driver.state!=AsyncItemSaveState.COMPLETED && driver.state!=AsyncItemSaveState.UNRESOLVED && System.nanoTime()<deadline) {driver.advance();Thread.yield()}
                assertEquals(AsyncItemSaveState.COMPLETED,driver.state,driver.reason);assertEquals(2,saves)
            } finally {worker.close();worker.drained.await()}
        }
        SqliteStorageBackend(path).use {db ->db.open();assertEquals(ItemRollbackPhase.COMPLETED,db.itemRollback(id!!)?.phase)}
        assertClaims(path,0)
    }
}
