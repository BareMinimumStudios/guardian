package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.*
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.*
import kotlin.test.*

/** Real SQLite reopen and claims; immutable synthetic ports do not certify Minecraft exclusion. */
class ItemRestartRecoveryHostTest {
    private class Fixture {
        val path=Files.createTempDirectory("guardian-restart-owner").resolve("audit.sqlite")
        val db=SqliteStorageBackend(path)
        val a=ItemSlotAddress(ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"),BlockPosition(1,64,1)),0)
        val b=ItemSlotAddress(ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"),BlockPosition(2,64,1)),0)
        val spare=ItemSlotAddress(a.owner,1)
        val item=ItemStackSnapshot(ResourceId.parse("minecraft:coal"),1,BinaryPayload.of(byteArrayOf(1,2)))
        val row=ContainerTransactionSnapshot(UUID.randomUUID(),100,ActorIdentity.System("hopper"),0,ContainerAction.HOPPER_TRANSFER,
            listOf(ItemSlotChange(a,item,ItemStackSnapshot.EMPTY),ItemSlotChange(b,ItemStackSnapshot.EMPTY,item)))
        val original=InventorySnapshot(mapOf(a to ItemStackSnapshot.EMPTY,b to item,spare to ItemStackSnapshot.EMPTY))
        val record: ItemRollbackRecord
        val expected: InventorySnapshot
        init {
            db.open();db.append(listOf(ContainerAuditEntry(row)))
            record=db.prepareItemRollback(UUID.randomUUID(),200,listOf(row))
            expected=ItemRollbackImages(record,original).expected
            check(db.protectItemRollback(record,original))
            check(db.transitionItemRollback(record.operationId,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING))
            db.close();db.open()
            check(db.itemRollback(record.operationId)!!.phase==ItemRollbackPhase.RECOVERY_REQUIRED)
        }
        fun receipt()=checkNotNull(db.itemRollbackImages(record.operationId))
        fun count(table: String)=DriverManager.getConnection("jdbc:sqlite:$path").use {connection ->
            connection.createStatement().use {it.executeQuery("SELECT COUNT(*) FROM $table").use {rows ->rows.next();rows.getInt(1)}}
        }
        fun assertProtected() {
            assertFalse(receipt().acknowledged);assertTrue(db.itemRollbackProtected(record.operationId))
            assertEquals(2,count("ex_item_rollback_owner"));assertEquals(1,count("ex_item_rollback_claim"))
        }
    }
    private class Port(private val fixture: Fixture) : ItemReconciliationPort,AutoCloseable {
        var quiescent=true
        var live=fixture.expected
        var saved=fixture.expected
        var pending: CompletableFuture<InventorySnapshot?>?=null
        var reads=0
        var closes=0
        var onClose: () -> Unit={}
        override fun isExclusiveAndQuiescent(owners: Set<ItemSlotOwner>)=quiescent
        override fun readLiveOwners(owners: Set<ItemSlotOwner>)=live
        override fun readSavedOwner(owner: ItemSlotOwner): CompletionStage<InventorySnapshot?> {
            reads++;return pending ?: CompletableFuture.completedFuture(InventorySnapshot(saved.slots.filterKeys {it.owner==owner}))
        }
        override fun close() {closes++;onClose()}
    }
    private fun until(host: ItemRestartRecoveryHost,condition: () -> Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!condition()) {check(System.nanoTime()<end){"${host.state}: ${host.reason}"};host.advance();Thread.sleep(1)}
    }
    private fun finish(host: ItemRestartRecoveryHost)=until(host) {host.state in setOf(ItemRestartRecoveryState.COMPLETED,ItemRestartRecoveryState.RECOVERY_REQUIRED)}
    private fun bind(port: Port,disk: CompletionStage<Void> = CompletableFuture.completedFuture(null))=ItemRestartRecoveryBinding(port,disk)
    private fun close(host: ItemRestartRecoveryHost,db: SqliteStorageBackend) {
        host.close();host.journalDrain.toCompletableFuture().get(5,TimeUnit.SECONDS);db.close()
    }

    @Test fun reopenedJournalReacquiresOnlyRevokedReadOwnershipAndAcknowledgesFullSavedImages() {
        val f=Fixture();val owners=ItemOwnerCoordination();val port=Port(f)
        val host=assertNotNull(ItemRestartRecoveryHost.start(f.receipt(),owners,f.db,{lease ->
            assertTrue(lease.isRetained(lease.owners));assertFalse(lease.isCurrent(lease.owners))
            lease.owners.forEach {assertFalse(owners.allowsMutation(it,lease));assertFalse(owners.allowsMutation(it))}
            bind(port)
        }))
        try {
            finish(host);assertEquals(ItemRestartRecoveryState.COMPLETED,host.state)
            assertTrue(f.receipt().acknowledged);assertFalse(owners.hasJournalRetention());assertEquals(1,port.closes)
            assertEquals(0,f.count("ex_item_rollback_owner"));assertEquals(1,f.count("ex_item_rollback_claim"))
            f.db.close();f.db.open();assertTrue(f.receipt().acknowledged);assertFalse(f.db.itemRollbackProtected(f.record.operationId))
        } finally {close(host,f.db)}
    }
    @Test fun actualDiskPrefixMustCompleteBeforeAnyJournalOrSavedRead() {
        val f=Fixture();val disk=CompletableFuture<Void>();val port=Port(f);val owners=ItemOwnerCoordination()
        val host=assertNotNull(ItemRestartRecoveryHost.start(f.receipt(),owners,f.db,{bind(port,disk.minimalCompletionStage())}))
        try {
            repeat(5){assertEquals(ItemRestartRecoveryState.WAITING_DISK,host.advance())}
            assertEquals(0,port.reads);f.assertProtected();assertTrue(owners.hasJournalRetention())
            disk.complete(null);finish(host);assertEquals(ItemRestartRecoveryState.COMPLETED,host.state)
        } finally {close(host,f.db)}
    }
    @Test fun failedDiskPrefixKeepsProtectionAndDoesNotReadSavedItems() {
        val f=Fixture();val disk=CompletableFuture<Void>();val port=Port(f);val owners=ItemOwnerCoordination()
        val host=assertNotNull(ItemRestartRecoveryHost.start(f.receipt(),owners,f.db,{bind(port,disk.minimalCompletionStage())}))
        try {disk.completeExceptionally(IllegalStateException("Disk drain failed"));finish(host);assertEquals(ItemRestartRecoveryState.RECOVERY_REQUIRED,host.state);assertEquals(0,port.reads);assertTrue(owners.hasJournalRetention());f.assertProtected()}
        finally {close(host,f.db)}
    }
    @Test fun fullLiveConflictNeverChangesItemsOrClearsClaims() {
        val f=Fixture();val owners=ItemOwnerCoordination();val port=Port(f).apply {live=f.original}
        val host=assertNotNull(ItemRestartRecoveryHost.start(f.receipt(),owners,f.db,{bind(port)}))
        try {finish(host);assertEquals(ItemRestartRecoveryState.RECOVERY_REQUIRED,host.state);assertEquals(f.original.slots,port.live.slots);assertTrue(owners.hasJournalRetention());f.assertProtected()}
        finally {close(host,f.db)}
    }
    @Test fun missingUnchangedSavedSlotKeepsOwnersAfterWorkerDrain() {
        val f=Fixture();val owners=ItemOwnerCoordination();val port=Port(f).apply {saved=InventorySnapshot(f.expected.slots-f.spare)}
        val host=assertNotNull(ItemRestartRecoveryHost.start(f.receipt(),owners,f.db,{bind(port)}))
        try {finish(host);assertTrue(host.isJournalDrained);assertEquals(ItemRestartRecoveryState.RECOVERY_REQUIRED,host.state);assertTrue(owners.hasJournalRetention());f.assertProtected()}
        finally {close(host,f.db)}
    }
    @Test fun unavailableBindingKeepsExactRevokedOwnersAndCanBeRetried() {
        val f=Fixture();val owners=ItemOwnerCoordination()
        val host=assertNotNull(ItemRestartRecoveryHost.start(f.receipt(),owners,f.db,{error("Unloaded inventory")}))
        try {
            finish(host);assertEquals(ItemRestartRecoveryState.RECOVERY_REQUIRED,host.state);assertTrue(owners.hasJournalRetention());f.assertProtected()
            host.retry(f.receipt().images.record) {bind(Port(f))};finish(host)
            assertEquals(ItemRestartRecoveryState.COMPLETED,host.state);assertFalse(owners.hasJournalRetention())
        } finally {close(host,f.db)}
    }
    @Test fun resolvedReceiptCannotAcquireFreshOwnerProtection() {
        val f=Fixture();val record=f.receipt().images.record;assertTrue(f.db.acknowledgeItemRollback(record,f.receipt().images,f.expected))
        val owners=ItemOwnerCoordination()
        try {assertFailsWith<IllegalArgumentException>{ItemRestartRecoveryHost.start(f.receipt(),owners,f.db,{error("Unexpected bind")})};assertFalse(owners.hasReservations())}
        finally {f.db.close()}
    }
    @Test fun overlappingOwnerRefusalDoesNotBindOrDropPersistentClaims() {
        val f=Fixture();val owners=ItemOwnerCoordination();val reserved=assertNotNull(owners.acquire(UUID.randomUUID(),listOf(f.a.owner)))
        try {assertNull(ItemRestartRecoveryHost.start(f.receipt(),owners,f.db,{error("Unexpected bind")}));assertTrue(reserved.isCurrent(reserved.owners));f.assertProtected()}
        finally {reserved.close();f.db.close()}
    }
    @Test fun stoppingPendingSavedReadDoesNotReleaseOrAcknowledgeLateResults() {
        val f=Fixture();val owners=ItemOwnerCoordination();val pending=CompletableFuture<InventorySnapshot?>();val port=Port(f).apply {this.pending=pending}
        val host=assertNotNull(ItemRestartRecoveryHost.start(f.receipt(),owners,f.db,{bind(port)}))
        try {
            until(host){port.reads>0};host.close();finish(host)
            pending.complete(f.expected);assertEquals(ItemRestartRecoveryState.RECOVERY_REQUIRED,host.advance())
            assertTrue(owners.hasJournalRetention());f.assertProtected();assertEquals(1,port.closes)
        } finally {close(host,f.db)}
    }
    @Test fun closeFromResourceCallbackCannotReleaseAfterDurableAcknowledgment() {
        val f=Fixture();val owners=ItemOwnerCoordination();val port=Port(f)
        val host=assertNotNull(ItemRestartRecoveryHost.start(f.receipt(),owners,f.db,{bind(port)}));port.onClose={host.close()}
        try {finish(host);assertEquals(ItemRestartRecoveryState.RECOVERY_REQUIRED,host.state);assertTrue(f.receipt().acknowledged);assertTrue(owners.hasJournalRetention())}
        finally {close(host,f.db)}
    }
    @Test fun stoppedHostCannotRetryOrUseHistoricalDecisionToReleaseItsHold() {
        val f=Fixture();val owners=ItemOwnerCoordination();val host=assertNotNull(ItemRestartRecoveryHost.start(f.receipt(),owners,f.db,{error("No inventory")}))
        try {host.close();finish(host);assertFailsWith<IllegalStateException>{host.retry(f.receipt().images.record){bind(Port(f))}};assertTrue(owners.hasJournalRetention());f.assertProtected()}
        finally {close(host,f.db)}
    }
    @Test fun retryRejectsChangedPlanBeforeCreatingAWorkerOrBinding() {
        val f=Fixture();val owners=ItemOwnerCoordination();val host=assertNotNull(ItemRestartRecoveryHost.start(f.receipt(),owners,f.db,{error("No inventory")}))
        try {finish(host);val old=f.receipt().images.record;assertFailsWith<IllegalArgumentException>{host.retry(ItemRollbackRecord(old.operationId,old.createdAt+1,old.phase,old.entries)){error("Unexpected binding")}};f.assertProtected()}
        finally {close(host,f.db)}
    }
    @Test fun callbackStopWhileCheckingQuiescenceCannotStartJournalReads() {
        val f=Fixture();val owners=ItemOwnerCoordination();var host: ItemRestartRecoveryHost?=null
        val port=object : ItemReconciliationPort {
            override fun isExclusiveAndQuiescent(owners: Set<ItemSlotOwner>): Boolean {host!!.close();return true}
            override fun readLiveOwners(owners: Set<ItemSlotOwner>): InventorySnapshot?=error("Stopped host cannot read")
            override fun readSavedOwner(owner: ItemSlotOwner): CompletionStage<InventorySnapshot?> = error("Stopped host cannot read")
        }
        host=assertNotNull(ItemRestartRecoveryHost.start(f.receipt(),owners,f.db,{ItemRestartRecoveryBinding(port,CompletableFuture.completedFuture(null))}))
        try {finish(host);assertEquals(ItemRestartRecoveryState.RECOVERY_REQUIRED,host.state);assertTrue(owners.hasJournalRetention());f.assertProtected()}
        finally {close(host,f.db)}
    }
    @Test fun cancelledDiskPrefixIsNotPhysicalDrainEvidence() {
        val f=Fixture();val owners=ItemOwnerCoordination();val disk=CompletableFuture<Void>();val port=Port(f)
        val host=assertNotNull(ItemRestartRecoveryHost.start(f.receipt(),owners,f.db,{bind(port,disk.minimalCompletionStage())}))
        try {disk.cancel(false);finish(host);assertEquals(ItemRestartRecoveryState.RECOVERY_REQUIRED,host.state);assertEquals(0,port.reads);f.assertProtected()}
        finally {close(host,f.db)}
    }
    @Test fun expiredWaitDoesNotReleaseRevokedOwners() {
        val f=Fixture();val owners=ItemOwnerCoordination();var now=0L;val disk=CompletableFuture<Void>()
        val host=assertNotNull(ItemRestartRecoveryHost.start(f.receipt(),owners,f.db,{bind(Port(f),disk.minimalCompletionStage())},{now}))
        try {now=TimeUnit.SECONDS.toNanos(11);finish(host);assertEquals(ItemRestartRecoveryState.RECOVERY_REQUIRED,host.state);assertTrue(host.lease.isRetained(host.lease.owners));f.assertProtected()}
        finally {close(host,f.db)}
    }
    @Test fun historicalAcknowledgmentCanResolveAnExplicitRetryWithoutResamplingChangedItems() {
        val f=Fixture();val owners=ItemOwnerCoordination();val host=assertNotNull(ItemRestartRecoveryHost.start(f.receipt(),owners,f.db,{error("Initially unavailable")}))
        try {
            finish(host);val before=f.receipt();assertTrue(f.db.acknowledgeItemRollback(before.images.record,before.images,f.expected))
            val port=Port(f).apply {live=f.original;saved=f.original}
            host.retry(f.receipt().images.record) {bind(port)};finish(host)
            assertEquals(ItemRestartRecoveryState.COMPLETED,host.state);assertEquals(0,port.reads)
            assertEquals(f.original.slots,port.live.slots);assertFalse(owners.hasJournalRetention())
        } finally {close(host,f.db)}
    }
    @Test fun unavailableQuiescenceNeverClearsPersistedClaims() {
        val f=Fixture();val owners=ItemOwnerCoordination();val port=Port(f).apply {quiescent=false}
        val host=assertNotNull(ItemRestartRecoveryHost.start(f.receipt(),owners,f.db,{bind(port)}))
        try {finish(host);assertEquals(ItemRestartRecoveryState.RECOVERY_REQUIRED,host.state);assertEquals(0,port.reads);assertTrue(owners.hasJournalRetention());f.assertProtected()}
        finally {close(host,f.db)}
    }
    @Test fun corruptReceiptOwnerClaimsCannotBeLoadedForRestartAdmission() {
        val f=Fixture()
        try {
            DriverManager.getConnection("jdbc:sqlite:${f.path}").use {connection ->connection.createStatement().use {it.executeUpdate("DELETE FROM ex_item_rollback_owner")}}
            assertFailsWith<IllegalArgumentException>{f.db.itemRollbackImages(f.record.operationId)}
            assertTrue(f.db.itemRollbackProtected(f.record.operationId));assertEquals(1,f.count("ex_item_rollback_claim"))
        } finally {f.db.close()}
    }

    @Test fun crossThreadControlsAndDrainAccessAreRefused() {
        val f=Fixture();val owners=ItemOwnerCoordination();val host=assertNotNull(ItemRestartRecoveryHost.start(f.receipt(),owners,f.db,{error("No inventory")}))
        val pool=Executors.newSingleThreadExecutor()
        try {pool.submit {assertFailsWith<IllegalStateException>{host.close()};assertFailsWith<IllegalStateException>{host.advance()};assertFailsWith<IllegalStateException>{host.journalDrain}}.get(5,TimeUnit.SECONDS)}
        finally {pool.shutdownNow();close(host,f.db)}
    }
    @Test fun stoppedStartedAcknowledgmentKeepsOwnersDespiteLateDurableDecision() {
        val f=Fixture();val owners=ItemOwnerCoordination();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val journal=object : ItemRollbackProtectionJournal by f.db {
            override fun acknowledgeItemRollback(record: ItemRollbackRecord,images: ItemRollbackImages,saved: InventorySnapshot): Boolean {
                entered.countDown();check(release.await(5,TimeUnit.SECONDS));return f.db.acknowledgeItemRollback(record,images,saved)
            }
        }
        val host=assertNotNull(ItemRestartRecoveryHost.start(f.receipt(),owners,journal,{bind(Port(f))}))
        try {
            until(host){entered.count==0L};host.close();val observed=host.journalDrain
            assertFalse(observed.toCompletableFuture().isDone);observed.toCompletableFuture().cancel(false)
            release.countDown();host.journalDrain.toCompletableFuture().get(5,TimeUnit.SECONDS);finish(host)
            assertTrue(f.receipt().acknowledged);assertTrue(owners.hasJournalRetention());assertEquals(ItemRestartRecoveryState.RECOVERY_REQUIRED,host.state)
        } finally {release.countDown();close(host,f.db)}
    }
}
