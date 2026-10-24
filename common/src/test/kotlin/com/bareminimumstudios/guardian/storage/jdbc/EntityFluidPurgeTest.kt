package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.*
import com.bareminimumstudios.guardian.storage.query.*
import com.bareminimumstudios.guardian.storage.purge.*
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test

class EntityFluidPurgeTest {
    private val actor=ActorIdentity.Player(UUID.randomUUID(),"PurgeFixture")
    private val world=ResourceId.parse("minecraft:overworld")
    private val position=BlockPosition(1,64,2)
    private val empty=ItemFrameSnapshot(ItemStackSnapshot.EMPTY,0)
    private val full=ItemFrameSnapshot(ItemStackSnapshot(ResourceId.parse("minecraft:diamond"),1,BinaryPayload.of(byteArrayOf(1,2,3))),1)
    private fun entity(time:Long,kind:EntityAuditKind=EntityAuditKind.DEATH)=EntityAuditEvent(timestampEpochMillis=time,actor=actor,dimension=world,position=position,targetUuid=UUID.randomUUID(),targetType=ResourceId.parse("minecraft:item_frame"),kind=kind,
        beforeName=if(kind==EntityAuditKind.NAME_CHANGE)"before"else null,afterName=if(kind==EntityAuditKind.NAME_CHANGE)"after"else null,
        parentUuid=if(kind==EntityAuditKind.BREED)UUID.randomUUID()else null,partnerUuid=if(kind==EntityAuditKind.BREED)UUID.randomUUID()else null,
        frameBefore=if(kind in setOf(EntityAuditKind.FRAME_REMOVE,EntityAuditKind.FRAME_CHANGE))empty else null,
        frameAfter=if(kind in setOf(EntityAuditKind.FRAME_PLACE,EntityAuditKind.FRAME_CHANGE))full else null)
    private fun fluid(time:Long)=FluidAuditEvent(timestampEpochMillis=time,actor=actor,dimension=world,position=position,kind=FluidAuditKind.PLACE,
        before=FluidSnapshot(FluidSnapshot.EMPTY,false,ResourceId.parse("minecraft:air")),after=FluidSnapshot(ResourceId.parse("minecraft:water"),true,ResourceId.parse("minecraft:water")))
    private fun activity(time:Long)=PlayerActivity(timestampEpochMillis=time,actor=actor,kind=PlayerActivityKind.CHAT,text="kept")
    private fun block(time:Long)=BlockChangeSnapshot(time,actor,world,position,BlockStateSnapshot(ResourceId.parse("air")),BlockStateSnapshot(ResourceId.parse("stone")),ChangeCause.PLAYER,ActionType.BLOCK_PLACE)
    private fun transaction(time:Long):ContainerTransactionSnapshot {
        val owner=ItemSlotOwner.BlockContainer(world,BlockPosition(10,64,0));val player=ItemSlotOwner.PlayerInventory(actor.uuid)
        return ContainerTransactionSnapshot(UUID.randomUUID(),time,actor,1,ContainerAction.PICKUP,listOf(
            ItemSlotChange(ItemSlotAddress(owner,0),ItemStackSnapshot.EMPTY,full.item),ItemSlotChange(ItemSlotAddress(player,0),full.item,ItemStackSnapshot.EMPTY)),listOf(owner))
    }
    private fun stores(test:(QueryableStorageBackend)->Unit) {
        listOf<QueryableStorageBackend>(InMemoryStorageBackend(),SqliteStorageBackend(Files.createTempDirectory("retention-sqlite").resolve("audit.db")),DuckDbStorageBackend(Files.createTempDirectory("retention-duck").resolve("audit.db"))).forEach { it.use { db->db.open();test(db) } }
    }
    @Test fun previewCountsEveryEntityKindAndFluidWithoutWritingAndKeepsCutoffRows()=stores { db->
        val rows=EntityAuditKind.entries.mapIndexed { i,k->entity(i+1L,k) };val recent=entity(100);val water=fluid(1);val fresh=fluid(100)
        db.append(rows+recent+water+fresh);val plan=db.previewPurge(100)
        assertEquals(PurgeCounts(entities=7,fluids=1),plan.eligible);assertEquals(8,plan.candidates.size)
        assertEquals(8,db.lookupEntities(EntityLookupQuery()).size);assertEquals(2,db.lookupFluids(FluidLookupQuery()).size)
        assertEquals(plan.eligible,db.applyPurge(plan){true}.deleted)
        assertEquals(listOf(recent),db.lookupEntities(EntityLookupQuery()));assertEquals(listOf(fresh),db.lookupFluids(FluidLookupQuery()))
    }
    @Test fun mixedCategoriesShareOneThousandKeysNotOneThousandPerTable()=stores { db->
        db.append((1..600).map{entity(1)}+(1..600).map{fluid(1)}+activity(1)+entity(100)+fluid(100)+activity(100))
        val plan=db.previewPurge(100);assertEquals(1201,plan.eligible.total);assertEquals(1000,plan.candidates.size)
        assertTrue(plan.candidates.any{it.kind==PurgeKind.ENTITY});assertTrue(plan.candidates.any{it.kind==PurgeKind.FLUID})
        assertEquals(1000,db.applyPurge(plan){true}.deleted.total);assertEquals(201,db.previewPurge(100).eligible.total)
        assertEquals(201,db.applyPurge(db.previewPurge(100)){true}.deleted.total)
        assertEquals(listOf(100L),db.lookupEntities(EntityLookupQuery()).map{it.timestampEpochMillis})
        assertEquals(listOf(100L),db.lookupFluids(FluidLookupQuery()).map{it.timestampEpochMillis})
    }
    @Test fun fixedKeysExcludeLateInsertsAndSeparateSameUuidAcrossTables()=stores { db->
        val id=UUID.randomUUID();val first=entity(2).copy(eventId=id);val water=fluid(3).copy(eventId=id)
        db.append(listOf(first,water));val plan=db.previewPurge(100);assertEquals(2,plan.candidates.size)
        val late=entity(1);val lateWater=fluid(1);db.append(listOf(late,lateWater));assertEquals(2,db.applyPurge(plan){true}.deleted.total)
        assertEquals(listOf(late),db.lookupEntities(EntityLookupQuery()));assertEquals(listOf(lateWater),db.lookupFluids(FluidLookupQuery()))
        val replay=db.applyPurge(plan){true};assertEquals(0,replay.deleted.total);assertEquals(2,replay.skipped)
    }
    @Test fun cancellationDuringMixedDeletionRollsBackFrameBlobsAndAllCategories()=stores { db->
        val frame=entity(1,EntityAuditKind.FRAME_PLACE);val water=fluid(2);db.append(listOf(frame,water,activity(3)))
        val plan=db.previewPurge(100);var checks=0;assertFails{db.applyPurge(plan){++checks<4}}
        assertEquals(plan.eligible,db.previewPurge(100).eligible)
        assertEquals(listOf(frame),db.lookupEntities(EntityLookupQuery()));assertEquals(listOf(water),db.lookupFluids(FluidLookupQuery()))
    }
    @Test fun cancellationImmediatelyBeforeCommitRetainsBothNewCategories()=stores { db->
        val frame=entity(1);val water=fluid(2);db.append(listOf(frame,water));var checks=0
        assertFails{db.applyPurge(db.previewPurge(100)){++checks<4}}
        assertEquals(PurgeCounts(entities=1,fluids=1),db.previewPurge(100).eligible)
    }
    @Test fun retainedItemJournalStillProtectsBlockAndItemHistoryWhileReadOnlyEventsCanPurge() {
        for(duck in listOf(false,true)) {
            val path=Files.createTempDirectory("retention-journal").resolve("audit.db");val db=if(duck)DuckDbStorageBackend(path)else SqliteStorageBackend(path)
            val item=transaction(2);val operation=UUID.randomUUID()
            db.use{store->store.open();store.append(listOf(block(1),ContainerAuditEntry(item),entity(3,EntityAuditKind.FRAME_PLACE),fluid(4)))
                store.prepareItemRollback(operation,5,listOf(item));val plan=store.previewPurge(100)
                assertEquals(PurgeCounts(1,1),plan.protected);assertEquals(PurgeCounts(entities=1,fluids=1),plan.eligible)
                assertEquals(plan.eligible,store.applyPurge(plan){true}.deleted)
                assertNotNull(store.itemRollback(operation));assertEquals(1,store.lookupBlocks(BlockLookupQuery()).size);val retained=store.lookupContainers(ContainerLookupQuery()).single();assertEquals(item.transactionId,retained.transactionId);assertEquals(item.changes,retained.changes);assertEquals(item.containers,retained.containers)
            }
            DriverManager.getConnection((if(duck)"jdbc:duckdb:"else "jdbc:sqlite:")+path).use { c->c.createStatement().use { s->
                s.executeQuery("SELECT meta_value FROM ex_meta WHERE meta_key='history_purged_before'").use{assertFalse(it.next())}
                s.executeQuery("SELECT COUNT(*) FROM ex_item_rollback_entry").use{assertTrue(it.next());assertEquals(1,it.getInt(1))}
            } }
        }
    }
    @Test fun entityFluidOnlyPurgePersistsWithoutAdvancingItemHistoryFloor() {
        for(duck in listOf(false,true)) {
            val path=Files.createTempDirectory("retention-restart").resolve("audit.db");fun db():QueryableStorageBackend=if(duck)DuckDbStorageBackend(path)else SqliteStorageBackend(path)
            val recent=entity(100);val fresh=fluid(100)
            db().use{it.open();it.append(listOf(entity(1),fluid(1),recent,fresh));assertEquals(2,it.applyPurge(it.previewPurge(100)){true}.deleted.total)}
            db().use{it.open();assertEquals(listOf(recent),it.lookupEntities(EntityLookupQuery()));assertEquals(listOf(fresh),it.lookupFluids(FluidLookupQuery()))}
            DriverManager.getConnection((if(duck)"jdbc:duckdb:"else "jdbc:sqlite:")+path).use { c->c.createStatement().use { s->s.executeQuery("SELECT meta_value FROM ex_meta WHERE meta_key='history_purged_before'").use{assertFalse(it.next())} } }
        }
    }
    @Test fun confirmationRechecksChangedTimesAndCannotDeleteRetimedRows() {
        for(duck in listOf(false,true)) {
            val path=Files.createTempDirectory("retention-retimed").resolve("audit.db");fun db():QueryableStorageBackend=if(duck)DuckDbStorageBackend(path)else SqliteStorageBackend(path)
            val frame=entity(1);val water=fluid(1)
            val plan=db().use{it.open();it.append(listOf(frame,water));it.previewPurge(100)}
            DriverManager.getConnection((if(duck)"jdbc:duckdb:"else "jdbc:sqlite:")+path).use { c->c.createStatement().use { s->s.executeUpdate("UPDATE ex_entity SET time=101");s.executeUpdate("UPDATE ex_fluid SET time=101")} }
            db().use{it.open();val result=it.applyPurge(plan){true};assertEquals(0,result.deleted.total);assertEquals(2,result.skipped);assertEquals(1,it.lookupEntities(EntityLookupQuery()).size);assertEquals(1,it.lookupFluids(FluidLookupQuery()).size)}
        }
    }
}
