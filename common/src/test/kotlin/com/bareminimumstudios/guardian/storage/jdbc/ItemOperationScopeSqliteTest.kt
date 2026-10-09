package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.*
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.*
import kotlin.test.*

/** Actual SQLite close/reopen; inventory ports are immutable synthetic fixtures, not Minecraft. */
class ItemOperationScopeSqliteTest {
    @Test fun stopBeforeRequestsKeepsPreparedClaimsAcrossRestart() = exercise(0)
    @Test fun stoppedIntentFinishesBeforeClosureAndReopensForRecovery() = exercise(1)
    @Test fun lateRealCommitClosesSafelyWithoutReleasingRuntimeProtection() = exercise(2)
    @Test fun confirmedHostReleasesOwnersAndDatabaseClosesCleanly() = exercise(3)

    private fun exercise(mode: Int) {
        val path=Files.createTempDirectory("guardian-ordered-shutdown").resolve("audit.sqlite")
        val dimension=ResourceId.parse("minecraft:overworld")
        val a=ItemSlotAddress(ItemSlotOwner.BlockContainer(dimension,BlockPosition(1,64,1)),0)
        val b=ItemSlotAddress(ItemSlotOwner.BlockContainer(dimension,BlockPosition(2,64,1)),0)
        val item=ItemStackSnapshot(ResourceId.parse("minecraft:coal"),2,BinaryPayload.of(byteArrayOf(1,2,3)))
        val row=ContainerTransactionSnapshot(UUID.randomUUID(),100,ActorIdentity.System("hopper"),0,
            ContainerAction.HOPPER_TRANSFER,listOf(ItemSlotChange(a,item,ItemStackSnapshot.EMPTY),ItemSlotChange(b,ItemStackSnapshot.EMPTY,item)))
        val db=SqliteStorageBackend(path);db.open();db.append(listOf(ContainerAuditEntry(row)))
        val record=db.prepareItemRollback(UUID.randomUUID(),200,listOf(row))
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val journal=object : ItemRollbackJournal by db {
            override fun transitionItemRollback(operationId: UUID,expected: ItemRollbackPhase,next: ItemRollbackPhase): Boolean {
                if ((mode==1 && next==ItemRollbackPhase.APPLYING) || (mode==2 && next==ItemRollbackPhase.COMPLETED)) {
                    entered.countDown();check(release.await(5,TimeUnit.SECONDS))
                }
                return db.transitionItemRollback(operationId,expected,next)
            }
        }
        var writes=0
        val live=linkedMapOf(a to ItemStackSnapshot.EMPTY,b to item)
        val port=object : ItemOperationPort {
            override fun isExclusiveAndCurrent(owners: Set<ItemSlotOwner>) = true
            override fun readOwners(owners: Set<ItemSlotOwner>) = InventorySnapshot(live)
            override fun writeSlot(address: ItemSlotAddress,expected: ItemStackSnapshot,replacement: ItemStackSnapshot) {
                assertEquals(expected,live[address]);live[address]=replacement;writes++
            }
            override fun saveAndReadBack(owner: ItemSlotOwner,addresses: Set<ItemSlotAddress>): CompletionStage<InventorySnapshot?> =
                CompletableFuture.completedFuture(InventorySnapshot(live.filterKeys { it.owner==owner }))
            override fun close() = Unit
        }
        val coordination=ItemOwnerCoordination()
        val closed=CountDownLatch(1)
        val main=Thread.currentThread()
        val scope=ItemOperationScope(journal) { check(Thread.currentThread()!==main);db.close();closed.countDown() }
        val host=assertNotNull(scope.start(record,coordination,{port}))
        fun until(condition: () -> Boolean,advance: () -> Unit) {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(!condition()) {check(System.nanoTime()<deadline);advance();Thread.sleep(1)}
        }
        try {
            if(mode==1 || mode==2) until({entered.count==0L},{host.advance()})
            if(mode==3) until({host.state==ItemOperationState.COMPLETED},{host.advance()})
            scope.close();scope.advance()
            if(mode==1 || mode==2) {assertEquals(1L,closed.count);assertTrue(coordination.hasJournalRetention())}
        } finally {
            release.countDown();scope.close()
            until({scope.state in setOf(ItemOperationScopeState.CLOSED,ItemOperationScopeState.FAILED)},{scope.advance()})
        }
        assertEquals(ItemOperationScopeState.CLOSED,scope.state)
        assertTrue(host.isJournalDrained);assertEquals(0L,closed.count)
        assertEquals(if(mode>=2)2 else 0,writes)
        assertEquals(mode!=3,coordination.hasJournalRetention())
        assertEquals(if(mode==3)ItemOperationState.COMPLETED else ItemOperationState.RECOVERY_REQUIRED,host.state)
        val expected=when(mode) {0->ItemRollbackPhase.PREPARED;1->ItemRollbackPhase.RECOVERY_REQUIRED;else->ItemRollbackPhase.COMPLETED}
        SqliteStorageBackend(path).use { reopened ->
            reopened.open();assertEquals(expected,reopened.itemRollback(record.operationId)?.phase)
            assertFalse(reopened.health().uncleanShutdownDetected)
            assertEquals(if(mode<=1)1 else 0,reopened.unfinishedItemRollbacks().size)
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            fun count(table: String)=connection.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM $table").use { result->result.next();result.getInt(1) } }
            assertEquals(1,count("ex_container"));assertEquals(1,count("ex_item_rollback_claim"))
            assertEquals(if(mode<=1)2 else 0,count("ex_item_rollback_owner"))
        }
        // A late COMPLETED journal still has no persistent unresolved-host receipt. Do not claim
        // automatic crash reconciliation: only in-process retention is established by this scope.
    }
}
