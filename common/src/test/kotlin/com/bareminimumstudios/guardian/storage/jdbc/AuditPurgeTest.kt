package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.*
import com.bareminimumstudios.guardian.storage.*
import com.bareminimumstudios.guardian.storage.purge.*
import com.bareminimumstudios.guardian.storage.query.*
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test

class AuditPurgeTest {
    private val actor=ActorIdentity.Player(UUID.randomUUID(),"PurgeFixture")
    private val world=ResourceId.parse("overworld")
    private fun block(time: Long,x: Int=0)=BlockChangeSnapshot(time,actor,world,BlockPosition(x,64,0),BlockStateSnapshot(ResourceId.parse("air")),BlockStateSnapshot(ResourceId.parse("stone")),ChangeCause.PLAYER,ActionType.BLOCK_PLACE)
    private fun item(time: Long):ContainerTransactionSnapshot {
        val owner=ItemSlotOwner.BlockContainer(world,BlockPosition(10,64,0));val player=ItemSlotOwner.PlayerInventory(actor.uuid)
        val coal=ItemStackSnapshot(ResourceId.parse("coal"),1,BinaryPayload.of(byteArrayOf(1)))
        return ContainerTransactionSnapshot(UUID.randomUUID(),time,actor,1,ContainerAction.PICKUP,listOf(ItemSlotChange(ItemSlotAddress(owner,0),ItemStackSnapshot.EMPTY,coal),ItemSlotChange(ItemSlotAddress(player,0),coal,ItemStackSnapshot.EMPTY)),listOf(owner))
    }
    private fun activity(time: Long,kind:PlayerActivityKind=PlayerActivityKind.CHAT)=PlayerActivity(timestampEpochMillis=time,actor=actor,kind=kind,text=if(kind==PlayerActivityKind.CHAT)"fixture"else null)
    private fun stores(test:(QueryableStorageBackend)->Unit) {
        listOf<QueryableStorageBackend>(InMemoryStorageBackend(),SqliteStorageBackend(Files.createTempDirectory("purge-sqlite").resolve("audit.db")),DuckDbStorageBackend(Files.createTempDirectory("purge-duck").resolve("audit.db"))).forEach { it.use { db->db.open();test(db) } }
    }
    @Test fun strictCutoffAllCategoriesAndPreviewIsReadOnly()=stores { db->
        db.append(listOf(block(1),block(100),ContainerAuditEntry(item(2)),ContainerAuditEntry(item(100)),activity(3),activity(4,PlayerActivityKind.LOGIN),activity(5,PlayerActivityKind.LOGOUT),activity(100)))
        val plan=db.previewPurge(100);assertEquals(PurgeCounts(1,1,3),plan.eligible);assertEquals(5,plan.candidates.size)
        assertEquals(2,db.lookupBlocks(BlockLookupQuery()).size)
        assertEquals(PurgeCounts(1,1,3),db.applyPurge(plan){true}.deleted)
        assertEquals(listOf(100L),db.lookupBlocks(BlockLookupQuery()).map{it.snapshot.timestampEpochMillis})
        assertEquals(listOf(100L),db.lookupContainers(ContainerLookupQuery()).map{it.timestampEpochMillis})
        assertEquals(1,db.lookupActivity(PlayerActivityQuery(PlayerActivityKind.entries.toSet())).size)
    }
    @Test fun confirmationNeverSelectsAnInsertAfterPreview()=stores { db->
        val first=block(1);db.append(listOf(first));val plan=db.previewPurge(100)
        val late=block(2,1);db.append(listOf(late));db.applyPurge(plan){true}
        assertEquals(listOf(late),db.lookupBlocks(BlockLookupQuery()).map{it.snapshot})
        assertEquals(0,db.applyPurge(plan){true}.deleted.total)
    }
    @Test fun pendingPositionsProtectWholeChainsEvenWhenPendingIsNewerThanCutoff()=stores { db->
        db.append(listOf(block(1),block(200),block(2,1),block(201,1),block(3,2)))
        val rows=db.lookupBlocks(BlockLookupQuery())
        db.setBlockRollbackState(listOf(rows.first{it.snapshot.timestampEpochMillis==200L}.rowId),BlockRollbackState.PENDING)
        db.setBlockRollbackState(listOf(rows.first{it.snapshot.timestampEpochMillis==201L}.rowId),BlockRollbackState.RESTORE_PENDING)
        val plan=db.previewPurge(100);assertEquals(2,plan.protected.blocks);assertEquals(1,plan.eligible.blocks)
        db.applyPurge(plan){true};assertEquals(4,db.lookupBlocks(BlockLookupQuery()).size)
    }
    @Test fun newlyPendingStateIsRecheckedBeforeDeletion()=stores { db->
        db.append(listOf(block(1)));val plan=db.previewPurge(100)
        db.setBlockRollbackState(listOf(db.lookupBlocks(BlockLookupQuery()).single().rowId),BlockRollbackState.RESTORE_PENDING)
        val result=db.applyPurge(plan){true};assertEquals(0,result.deleted.total);assertEquals(1,result.skipped)
        assertEquals(BlockRollbackState.RESTORE_PENDING,db.lookupBlocks(BlockLookupQuery()).single().rollbackState)
    }
    @Test fun oneThousandLimitAndRepeatNeverTouchesNewerData()=stores { db->
        db.append((1L..1002L).map { activity(it) }+activity(2000))
        val first=db.previewPurge(1500);assertEquals(1002,first.eligible.activity);assertEquals(1000,first.candidates.size)
        assertEquals(1000,db.applyPurge(first){true}.deleted.total)
        assertEquals(2,db.applyPurge(db.previewPurge(1500)){true}.deleted.total)
        assertEquals(listOf(2000L),db.lookupActivity(PlayerActivityQuery(setOf(PlayerActivityKind.CHAT))).map{it.timestampEpochMillis})
    }
    @Test fun withdrawalDuringBatchRollsBackEverything()=stores { db->
        db.append(listOf(block(1),ContainerAuditEntry(item(2)),activity(3)))
        val plan=db.previewPurge(100);var checks=0
        assertFails { db.applyPurge(plan){++checks<4} }
        assertEquals(PurgeCounts(1,1,1),db.previewPurge(100).eligible)
    }
    @Test fun confirmationIsBoundExpiringAndSingleUse() {
        var now=0L;val tickets=PurgeTickets { now };val plan=AuditPurgePlan(100,PurgeCounts(1),PurgeCounts(),listOf(PurgeCandidate(PurgeKind.BLOCK,"1",1)))
        val ticket=tickets.offer("Alex",plan)
        assertNull(tickets.take("Bob",ticket.token));assertNull(tickets.take("Alex",UUID.randomUUID()))
        assertSame(plan,tickets.take("Alex",ticket.token));assertNull(tickets.take("Alex",ticket.token))
        val expired=tickets.offer("Alex",plan);now=60_000_000_000L;assertNull(tickets.take("Alex",expired.token))
        val cancelled=tickets.offer("Alex",plan);assertFalse(tickets.cancel("Bob"));assertTrue(tickets.cancel("Alex"));assertNull(tickets.take("Alex",cancelled.token))
    }
    @Test fun planCannotBeExpandedOrMutatedAfterPreview() {
        val values=mutableListOf(PurgeCandidate(PurgeKind.BLOCK,"1",1));val plan=AuditPurgePlan(100,PurgeCounts(1),PurgeCounts(),values)
        values.clear();assertEquals(1,plan.candidates.size)
        assertFailsWith<UnsupportedOperationException>{(plan.candidates as MutableList<PurgeCandidate>).clear()}
        assertFailsWith<IllegalArgumentException>{AuditPurgePlan(100,PurgeCounts(),PurgeCounts(),(1..1001).map{PurgeCandidate(PurgeKind.BLOCK,it.toString(),1)})}
        assertFailsWith<IllegalArgumentException>{AuditPurgePlan(100,PurgeCounts(),PurgeCounts(),listOf(PurgeCandidate(PurgeKind.BLOCK,"1",100)))}
    }
    @Test fun retainedJournalProtectsHistoryAndItsChildIndexesAcrossRestart() {
        for(duck in listOf(false,true)) {
            val file=Files.createTempDirectory("purge-journal").resolve("audit.db");fun db():JdbcStorageBackend=if(duck)DuckDbStorageBackend(file)else SqliteStorageBackend(file)
            val transaction=item(20);val operation=UUID.randomUUID()
            db().use { store->store.open();store.append(listOf(block(1),ContainerAuditEntry(transaction),activity(30)));store.prepareItemRollback(operation,40,listOf(transaction))
                val plan=store.previewPurge(100);assertEquals(PurgeCounts(0,0,1),plan.eligible);assertEquals(PurgeCounts(1,1),plan.protected)
                assertEquals(1,store.applyPurge(plan){true}.deleted.activity);assertEquals(1,store.itemRollback(operation)?.entries?.size)
            }
            db().use { store->store.open();assertEquals(1,store.lookupContainers(ContainerLookupQuery()).size);assertEquals(1,store.lookupBlocks(BlockLookupQuery()).size);assertNotNull(store.itemRollback(operation)) }
            DriverManager.getConnection((if(duck)"jdbc:duckdb:"else "jdbc:sqlite:")+file).use { conn->conn.createStatement().use { statement->for(table in listOf("ex_container_owner","ex_container_location","ex_container_item","ex_item_rollback_entry","ex_item_rollback_owner","ex_item_rollback_claim"))statement.executeQuery("SELECT COUNT(*) FROM $table").use { it.next();assertTrue(it.getInt(1)>0,table) } } }
        }
    }
    @Test fun containerIndexesRemovedAtomicallyAndWatermarkSurvivesRestart() {
        for(duck in listOf(false,true)) {
            val file=Files.createTempDirectory("purge-floor").resolve("audit.db");fun db():JdbcStorageBackend=if(duck)DuckDbStorageBackend(file)else SqliteStorageBackend(file)
            val old=item(1);val recent=item(200)
            db().use { it.open();it.append(listOf(ContainerAuditEntry(old),ContainerAuditEntry(recent)));it.applyPurge(it.previewPurge(100)){true} }
            db().use { store->store.open();assertEquals(listOf(recent.transactionId),store.lookupContainers(ContainerLookupQuery()).map{it.transactionId})
                assertEquals(recent.changes,store.lookupContainers(ContainerLookupQuery()).single().changes)
                assertTrue(store.guardContainerHistory(listOf(old)).incompleteTransactions.contains(old.transactionId))
                assertTrue(store.guardContainerHistory(listOf(recent)).clear)
                store.append(listOf(ContainerAuditEntry(old)))
                assertFails { store.prepareItemRollback(UUID.randomUUID(),300,listOf(old)) }
            }
            DriverManager.getConnection((if(duck)"jdbc:duckdb:"else "jdbc:sqlite:")+file).use { conn->conn.createStatement().use { statement->
                statement.executeQuery("SELECT COUNT(*) FROM ex_container_item WHERE transaction_uuid='${recent.transactionId}'").use{it.next();assertEquals(1,it.getInt(1))}
                statement.executeQuery("SELECT meta_value FROM ex_meta WHERE meta_key='history_purged_before'").use{assertTrue(it.next());assertEquals("100",it.getString(1))}
            } }
        }
    }
    @Test fun journalCreatedAfterPreviewProtectsItsPreviouslySelectedHistory() {
        for(duck in listOf(false,true)) {
            val file=Files.createTempDirectory("purge-late-journal").resolve("audit.db")
            val db=if(duck)DuckDbStorageBackend(file)else SqliteStorageBackend(file)
            db.use { store->store.open();val row=item(20);store.append(listOf(block(1),ContainerAuditEntry(row),activity(30)))
                val plan=store.previewPurge(100);assertEquals(3,plan.candidates.size)
                val id=UUID.randomUUID();store.prepareItemRollback(id,40,listOf(row))
                val result=store.applyPurge(plan){true};assertEquals(PurgeCounts(0,0,1),result.deleted);assertEquals(2,result.skipped);assertNotNull(store.itemRollback(id))
            }
        }
    }
    @Test fun cancellationImmediatelyBeforeCommitAlsoRollsBackTheWatermark() {
        for(duck in listOf(false,true)) {
            val file=Files.createTempDirectory("purge-cancel-commit").resolve("audit.db")
            val db=if(duck)DuckDbStorageBackend(file)else SqliteStorageBackend(file)
            db.use { store->store.open();val row=item(20);store.append(listOf(block(1),ContainerAuditEntry(row)))
                var checks=0;assertFails{store.applyPurge(store.previewPurge(100)){++checks<4}}
                assertEquals(PurgeCounts(1,1),store.previewPurge(100).eligible)
                assertTrue(store.guardContainerHistory(listOf(row)).incompleteTransactions.isEmpty())
            }
        }
    }
    @Test fun schema13UpgradeDoesNotDeleteOrRewriteHistory() {
        for(duck in listOf(false,true)) {
            val file=Files.createTempDirectory("purge-upgrade").resolve("audit.db")
            fun db():JdbcStorageBackend=if(duck)DuckDbStorageBackend(file)else SqliteStorageBackend(file)
            val old=item(2);val audit=block(1);val chat=activity(3)
            db().use { it.open();it.append(listOf(audit,ContainerAuditEntry(old),chat)) }
            DriverManager.getConnection((if(duck)"jdbc:duckdb:"else "jdbc:sqlite:")+file).use { conn->conn.createStatement().use { statement->
                statement.executeUpdate("DROP TABLE ex_entity");statement.executeUpdate("DROP TABLE IF EXISTS ex_fluid");statement.executeUpdate("DELETE FROM ex_schema_migrations WHERE version>=14")
                statement.executeUpdate("UPDATE ex_meta SET meta_value='13' WHERE meta_key='schema_version'")
            } }
            db().use { store->store.open();assertEquals(20,store.health().schemaVersion)
                assertEquals(audit,store.lookupBlocks(BlockLookupQuery()).single().snapshot)
                val item=store.lookupContainers(ContainerLookupQuery()).single();assertEquals(old.transactionId,item.transactionId);assertEquals(old.changes,item.changes)
                assertEquals(listOf(chat),store.lookupActivity(PlayerActivityQuery(setOf(PlayerActivityKind.CHAT))))
            }
        }
    }
    @Test fun plannerRejectsIncompleteHistoryExplicitly() {
        val transaction=item(1);val live=InventorySnapshot(transaction.changes.associate{it.address to it.after})
        val preview=ContainerRollbackPlanner.plan(listOf(transaction),live,historyGuard=ContainerHistoryGuard(incompleteTransactions=setOf(transaction.transactionId)))
        assertEquals(ContainerPreviewReason.INCOMPLETE_HISTORY,preview.entries.single().reason)
    }
}
