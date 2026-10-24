package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.*
import com.bareminimumstudios.guardian.storage.purge.*
import com.bareminimumstudios.guardian.storage.query.*
import com.bareminimumstudios.guardian.command.PurgeCommandFilters
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test

class ScopedPurgeTest {
    private val actor=ActorIdentity.Player(UUID.randomUUID(),"ScopedFixture")
    private val world=ResourceId.parse("overworld")
    private val nether=ResourceId.parse("the_nether")
    private val stone=ResourceId.parse("stone")
    private val coal=ResourceId.parse("coal")
    private fun block(time: Long, dimension: ResourceId=world, id: ResourceId=stone)=BlockChangeSnapshot(time,actor,dimension,BlockPosition(time.toInt(),64,0),BlockStateSnapshot(ResourceId.parse("air")),BlockStateSnapshot(id),ChangeCause.PLAYER,ActionType.BLOCK_PLACE)
    private fun activity()=PlayerActivity(timestampEpochMillis=1,actor=actor,kind=PlayerActivityKind.CHAT,text="retained")
    private fun entity(dimension: ResourceId=world)=EntityAuditEvent(timestampEpochMillis=1,actor=actor,dimension=dimension,position=BlockPosition(0,64,0),targetUuid=UUID.randomUUID(),targetType=ResourceId.parse("pig"),kind=EntityAuditKind.DEATH)
    private fun fluid(dimension: ResourceId=world)=FluidAuditEvent(timestampEpochMillis=1,actor=actor,dimension=dimension,position=BlockPosition(0,64,0),kind=FluidAuditKind.PLACE,before=FluidSnapshot(FluidSnapshot.EMPTY,false,ResourceId.parse("air")),after=FluidSnapshot(ResourceId.parse("water"),true,ResourceId.parse("water")))
    private fun item(worlds: List<ResourceId> = listOf(world), ids: List<ResourceId> = listOf(coal)): ContainerAuditEntry {
        val owners=worlds.mapIndexed { i,w->ItemSlotOwner.BlockContainer(w,BlockPosition(i,64,0)) }
        val changes=ids.mapIndexed { i,id->ItemSlotChange(ItemSlotAddress(owners.getOrNull(i%owners.size.coerceAtLeast(1)) ?: ItemSlotOwner.PlayerInventory(actor.uuid),i),ItemStackSnapshot.EMPTY,ItemStackSnapshot(id,1,BinaryPayload.of(byteArrayOf(1)))) }
        return ContainerAuditEntry(ContainerTransactionSnapshot(UUID.randomUUID(),1,actor,1,ContainerAction.PICKUP,changes,owners))
    }
    private fun stores(test: (QueryableStorageBackend)->Unit) {
        listOf<QueryableStorageBackend>(InMemoryStorageBackend(),SqliteStorageBackend(Files.createTempDirectory("scope-sqlite").resolve("audit.db")),DuckDbStorageBackend(Files.createTempDirectory("scope-duck").resolve("audit.db"))).forEach { it.use { db->db.open();test(db) } }
    }
    @Test fun worldOnlyRetainsUnlocatedItemsAndActivity()=stores { db->
        db.append(listOf(block(1),block(2,nether),item(),item(listOf(nether)),item(emptyList()),activity(),entity(),entity(nether),fluid(),fluid(nether)))
        val plan=db.previewPurge(100,1000,PurgeScope(world))
        assertEquals(PurgeCounts(1,1,0,1,1),plan.eligible)
        assertEquals(plan.eligible,db.applyPurge(plan){true}.deleted)
        assertEquals(PurgeCounts(1,2,1,1,1),db.previewPurge(100).eligible)
    }
    @Test fun typesMatchBothBlockEndsAndKeepOtherCategories()=stores { db->
        db.append(listOf(block(1),block(2,nether),block(3,id=coal),item(),activity(),entity(),fluid()))
        val plan=db.previewPurge(100,1000,PurgeScope(include=setOf(stone,coal),exclude=setOf(ResourceId.parse("air"))))
        assertEquals(PurgeCounts(containers=1),plan.eligible)
        assertEquals(plan.eligible,db.applyPurge(plan){true}.deleted)
        assertEquals(PurgeCounts(3,0,1,1,1),db.previewPurge(100).eligible)
    }
    @Test fun matchingOneEndpointDeletesWholeCrossWorldTransaction()=stores { db->
        db.append(listOf(item(listOf(world,nether),listOf(coal,stone))))
        val excluded=db.previewPurge(100,1000,PurgeScope(world,include=setOf(coal),exclude=setOf(stone)))
        assertEquals(0,excluded.eligible.total)
        val plan=db.previewPurge(100,1000,PurgeScope(world,include=setOf(coal)))
        assertEquals(1,db.applyPurge(plan){true}.deleted.containers)
        assertTrue(db.lookupContainers(ContainerLookupQuery(dimension=nether)).isEmpty())
        assertEquals(0,db.previewPurge(100).eligible.total)
    }
    @Test fun excludeOnlyStillSelectsOnlyBlockAndItemHistory()=stores { db->
        db.append(listOf(block(1),item(),activity(),entity(),fluid()))
        val plan=db.previewPurge(100,1000,PurgeScope(exclude=setOf(coal)))
        assertEquals(PurgeCounts(blocks=1),plan.eligible)
        assertEquals(plan.eligible,db.applyPurge(plan){true}.deleted)
    }
    @Test fun scopedCountsAndApplyRespectPendingPositions()=stores { db->
        db.append(listOf(block(1),block(2,nether)))
        val row=db.lookupBlocks(BlockLookupQuery()).first { it.snapshot.dimension==world }
        val scope=PurgeScope(world,include=setOf(stone))
        val plan=db.previewPurge(100,1000,scope)
        db.setBlockRollbackState(listOf(row.rowId),BlockRollbackState.PENDING)
        assertEquals(1,db.applyPurge(plan){true}.skipped)
        val next=db.previewPurge(100,1000,scope)
        assertEquals(0,next.eligible.total);assertEquals(1,next.protected.blocks)
    }
    @Test fun scopeCopiesInputsAndFixedBatchExcludesLateRows()=stores { db->
        val ids=linkedSetOf(stone);val scope=PurgeScope(world,ids);ids.clear();ids.add(coal)
        assertEquals(setOf(stone),scope.include)
        assertFailsWith<UnsupportedOperationException>{(scope.include as MutableSet<ResourceId>).clear()}
        db.append(listOf(block(1),block(2,id=coal)))
        val plan=db.previewPurge(100,1000,scope);db.append(listOf(block(3)))
        assertEquals(1,db.applyPurge(plan){true}.deleted.total)
        assertEquals(2,db.previewPurge(100).eligible.blocks)
    }
    @Test fun scopedBatchCapIsSharedAndCancellationIsAtomic()=stores { db->
        db.append((1L..1001).map { block(it) }+item())
        val scope=PurgeScope(world,include=setOf(stone,coal));val plan=db.previewPurge(2000,1000,scope)
        assertEquals(1002,plan.eligible.total);assertEquals(1000,plan.candidates.size)
        var checks=0;assertFails { db.applyPurge(plan){++checks<4} }
        assertEquals(plan.eligible,db.previewPurge(2000,1000,scope).eligible)
        assertEquals(1000,db.applyPurge(plan){true}.deleted.total)
        assertEquals(2,db.previewPurge(2000,1000,scope).eligible.total)
    }
    @Test fun parserRejectsUnsupportedDuplicateAndMalformedScopes() {
        for(raw in listOf("", "w:overworld", "t:30d u:Alex", "t:30d time:7d", "t:30d w:overworld world:the_nether", "t:30d i:coal,", "t:30d i:*", "t:30d w:a,b", "t:30d e:", "t:30d i:coal include:stone", "t:30d i:"+(1..65).joinToString(","){"mod:item$it"}))assertTrue(PurgeCommandFilters.parse(raw).isFailure,raw)
        val result=PurgeCommandFilters.parse("time:30d world:overworld include:stone,mod:ore exclude:coal").getOrThrow()
        assertEquals(30L*86400000,result.ageMillis);assertEquals(world,result.scope.world);assertEquals(setOf(stone,ResourceId.parse("mod:ore")),result.scope.include)
    }
    @Test fun suggestionsPreserveCommaHeadsAndCanonicalWorldIds() {
        assertEquals(listOf("w:minecraft:overworld"),PurgeCommandFilters.suggestions("w:over",listOf("minecraft:overworld"),emptyList()))
        assertEquals(listOf("i:minecraft:stone,minecraft:coal"),PurgeCommandFilters.suggestions("i:minecraft:stone,co",emptyList(),listOf("minecraft:stone","minecraft:coal")))
        assertEquals(listOf("t:30d","t:30w"),PurgeCommandFilters.suggestions("t:30",emptyList(),emptyList()))
        assertTrue(PurgeCommandFilters.suggestions("u:",emptyList(),emptyList()).isEmpty())
    }
    @Test fun jdbcConfirmationRechecksWorldAndResourceIndexes() {
        val path=Files.createTempDirectory("scope-recheck").resolve("audit.db")
        SqliteStorageBackend(path).use { db->
            db.open();db.append(listOf(block(1),block(2,nether),item()))
            val plan=db.previewPurge(100,1000,PurgeScope(world,include=setOf(stone,coal)))
            assertEquals(2,plan.candidates.size)
            java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { conn->
                conn.createStatement().use { it.executeUpdate("UPDATE ex_block SET wid=(SELECT id FROM ex_world_map WHERE world='minecraft:the_nether') WHERE time=1")
                    it.executeUpdate("DELETE FROM ex_container_item") }
            }
            val result=db.applyPurge(plan){true};assertEquals(0,result.deleted.total);assertEquals(2,result.skipped)
            assertEquals(2,db.lookupBlocks(BlockLookupQuery()).size);assertEquals(1,db.lookupContainers(ContainerLookupQuery()).size)
        }
    }
    @Test fun scopedDeletionKeepsIndexCleanupAndConservativeCompletenessMarkerAtomic() {
        val path=Files.createTempDirectory("scope-floor").resolve("audit.db")
        SqliteStorageBackend(path).use { db->
            db.open();db.append(listOf(item(listOf(world,nether),listOf(coal,stone)),entity()))
            val scope=PurgeScope(world,include=setOf(coal));val plan=db.previewPurge(100,1000,scope)
            var checks=0;assertFails { db.applyPurge(plan){++checks<3} }
            java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { conn->
                fun count(table:String)=conn.createStatement().use { q->q.executeQuery("SELECT COUNT(*) FROM $table").use { it.next();it.getInt(1) } }
                assertEquals(2,count("ex_container_location"));assertEquals(2,count("ex_container_item"))
                assertEquals(1,db.applyPurge(plan){true}.deleted.containers)
                for(table in listOf("ex_container","ex_container_item","ex_container_owner","ex_container_location"))assertEquals(0,count(table),table)
                conn.createStatement().use { q->q.executeQuery("SELECT meta_value FROM ex_meta WHERE meta_key='history_purged_before'").use { assertTrue(it.next());assertEquals("100",it.getString(1)) } }
                assertEquals(1,db.applyPurge(db.previewPurge(200,1000,PurgeScope(world))){true}.deleted.entities)
                conn.createStatement().use { q->q.executeQuery("SELECT meta_value FROM ex_meta WHERE meta_key='history_purged_before'").use { assertTrue(it.next());assertEquals("100",it.getString(1)) } }
            }
        }
    }
}
