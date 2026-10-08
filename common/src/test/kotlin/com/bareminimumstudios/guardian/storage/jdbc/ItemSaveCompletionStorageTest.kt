package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.*
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.*
import kotlin.test.*

/** Real SQLite journal, controlled save port; no claim of Minecraft disk/crash acceptance. */
class ItemSaveCompletionStorageTest {
    private val dimension=ResourceId.parse("minecraft:overworld")
    private val a=ItemSlotAddress(ItemSlotOwner.BlockContainer(dimension,BlockPosition(1,64,1)),0)
    private val b=ItemSlotAddress(ItemSlotOwner.BlockContainer(dimension,BlockPosition(2,64,1)),0)
    private val item=ItemStackSnapshot(ResourceId.parse("minecraft:coal"),1,BinaryPayload.of(byteArrayOf(1)))
    private fun row()=ContainerTransactionSnapshot(UUID.randomUUID(),1,ActorIdentity.System("hopper"),0,ContainerAction.HOPPER_TRANSFER,listOf(ItemSlotChange(a,item,ItemStackSnapshot.EMPTY),ItemSlotChange(b,ItemStackSnapshot.EMPTY,item)))
    private fun port(failing: Boolean)=object: ItemSavePort {
        override fun isExclusiveAndCurrent(owners: Set<ItemSlotOwner>)=true
        override fun saveAndReadBack(owner: ItemSlotOwner,addresses: Set<ItemSlotAddress>): CompletionStage<InventorySnapshot?> {
            if(failing && owner==b.owner) return CompletableFuture.failedFuture(IllegalStateException("Synthetic second-owner disk failure"))
            return CompletableFuture.completedFuture(InventorySnapshot(addresses.associateWith { if(it==a) item else ItemStackSnapshot.EMPTY }))
        }
    }
    @Test fun partialSaveFailureRetainsClaimsAndRestartRequiresRecovery() {
        val path=Files.createTempDirectory("guardian-save-failure").resolve("audit.sqlite");val row=row();val id=UUID.randomUUID()
        SqliteStorageBackend(path).use { db ->
            db.open();db.append(listOf(ContainerAuditEntry(row)));db.prepareItemRollback(id,2,listOf(row));db.transitionItemRollback(id,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING)
            val driver=ItemSaveCompletion(db.itemRollback(id)!!,db,port(true));repeat(4) { driver.advance() }
            assertEquals(ItemSaveCompletionState.UNRESOLVED,driver.state);assertEquals(ItemRollbackPhase.APPLYING,db.itemRollback(id)?.phase)
        }
        SqliteStorageBackend(path).use { db -> db.open();assertEquals(ItemRollbackPhase.RECOVERY_REQUIRED,db.itemRollback(id)?.phase);assertEquals(1,db.health().unfinishedItemRollbacks) }
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection -> connection.createStatement().use { query ->
            query.executeQuery("select count(*) from ex_item_rollback_owner").use { assertTrue(it.next());assertEquals(2,it.getInt(1)) }
            query.executeQuery("select count(*) from ex_item_rollback_claim").use { assertTrue(it.next());assertEquals(1,it.getInt(1)) }
        } }
    }
    @Test fun verifiedReadbacksCompleteJournalButRetainSourceClaimAcrossRestart() {
        val path=Files.createTempDirectory("guardian-save-success").resolve("audit.sqlite");val row=row();val id=UUID.randomUUID()
        SqliteStorageBackend(path).use { db ->
            db.open();db.append(listOf(ContainerAuditEntry(row)));db.prepareItemRollback(id,2,listOf(row));db.transitionItemRollback(id,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING)
            val driver=ItemSaveCompletion(db.itemRollback(id)!!,db,port(false));repeat(4) { driver.advance() };assertEquals(ItemSaveCompletionState.COMPLETED,driver.state)
        }
        SqliteStorageBackend(path).use { db ->
            db.open();assertEquals(ItemRollbackPhase.COMPLETED,db.itemRollback(id)?.phase);assertEquals(0,db.health().unfinishedItemRollbacks)
            assertFails { db.prepareItemRollback(UUID.randomUUID(),3,listOf(row)) }
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection -> connection.createStatement().use { query ->
            query.executeQuery("select count(*) from ex_item_rollback_owner").use { assertTrue(it.next());assertEquals(0,it.getInt(1)) }
            query.executeQuery("select count(*) from ex_item_rollback_claim").use { assertTrue(it.next());assertEquals(1,it.getInt(1)) }
        } }
    }
}
