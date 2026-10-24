package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.*
import com.bareminimumstudios.guardian.storage.query.*
import com.bareminimumstudios.guardian.command.*
import com.bareminimumstudios.guardian.rollback.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test

class BlockRecoveryFiltersTest {
    private val alice=ActorIdentity.Player(UUID.randomUUID(),"Alice")
    private val bob=ActorIdentity.Player(UUID.randomUUID(),"Bob")
    private val carol=ActorIdentity.Player(UUID.randomUUID(),"Carol")
    private val world=ResourceId.parse("overworld")
    private fun state(id:String)=BlockStateSnapshot(ResourceId.parse(id))
    private fun row(t:Long,actor:ActorIdentity,before:String="air",after:String="stone")=BlockChangeSnapshot(t,actor,world,BlockPosition(0,64,0),state(before),state(after),ChangeCause.PLAYER,ActionType.BLOCK_CHANGE)
    private fun search(raw:String)=BlockCommandFilterParser.parse(raw).getOrThrow().search.resolve(mapOf("Alice" to alice.uuid,"Bob" to bob.uuid))
    private fun stores(test:(QueryableStorageBackend)->Unit) {
        listOf<QueryableStorageBackend>(InMemoryStorageBackend(),SqliteStorageBackend(Files.createTempDirectory("recovery-filter-sqlite").resolve("audit.db")),DuckDbStorageBackend(Files.createTempDirectory("recovery-filter-duck").resolve("audit.db"))).forEach { it.use { db->db.open();test(db) } }
    }
    @Test fun multiUserAndBlockFiltersPrecedeTheRecoveryRecordCap()=stores { db->
        val a=row(1,alice);val b=row(2,bob,after="example:ore");val c=row(3,carol)
        db.append(listOf(a,b,c,row(4,alice,after="dirt")))
        val q=BlockLookupQuery(search=search("u:Alice,Bob i:stone,example:ore"),rollbackStates=BlockHistoryOperation.ROLLBACK.eligibleStates,limit=2)
        assertEquals(listOf(b,a),db.lookupBlocks(q).map{it.snapshot})
        assertEquals(listOf(a),db.lookupBlocks(q.copy(search=search("u:Alice,Bob,!Bob i:stone,example:ore"))).map{it.snapshot})
    }
    @Test fun restoreUsesOriginalResourceStatesAndOldestFirstOrdering()=stores { db->
        val a=row(1,alice,"air","stone");val b=row(2,bob,"stone","dirt")
        db.append(listOf(a,b,row(3,carol)))
        db.setBlockRollbackState(db.lookupBlocks(BlockLookupQuery()).map{it.rowId},BlockRollbackState.ROLLED_BACK)
        val q=BlockLookupQuery(search=search("u:Alice,Bob i:stone"),rollbackStates=BlockHistoryOperation.RESTORE.eligibleStates,oldestFirst=true)
        val selected=db.lookupBlocks(q);assertEquals(listOf(a,b),selected.map{it.snapshot})
        val plan=BlockRollbackPreviewPlan(BlockHistoryOperation.RESTORE)
        assertEquals(listOf(BlockRollbackPreviewPlan.Outcome.WOULD_RESTORE,BlockRollbackPreviewPlan.Outcome.WOULD_RESTORE),selected.map{plan.observe(it,state("air"))})
        assertEquals(3,db.lookupBlocks(BlockLookupQuery(rollbackStates=setOf(BlockRollbackState.ROLLED_BACK))).size)
    }
    @Test fun excludedBlockVetoesBothRecordedEndsWithoutChangingJournalStates()=stores { db->
        db.append(listOf(row(1,alice,"stone","dirt"),row(2,bob,"air","stone")))
        val selected=db.lookupBlocks(BlockLookupQuery(search=search("u:Alice,Bob i:stone e:dirt"),rollbackStates=BlockHistoryOperation.ROLLBACK.eligibleStates))
        assertEquals(listOf(2L),selected.map{it.snapshot.timestampEpochMillis})
        assertTrue(db.lookupBlocks(BlockLookupQuery()).all{it.rollbackState==BlockRollbackState.ACTIVE})
    }
    @Test fun filteredGapsRemainBlockedByLiveStateComparison()=stores { db->
        db.append(listOf(row(1,alice,"air","stone"),row(2,carol,"stone","dirt"),row(3,bob,"dirt","gold_block")))
        val rows=db.lookupBlocks(BlockLookupQuery(search=search("u:Alice,Bob"),rollbackStates=BlockHistoryOperation.ROLLBACK.eligibleStates))
        val plan=BlockRollbackPreviewPlan()
        assertEquals(listOf(BlockRollbackPreviewPlan.Outcome.WOULD_RESTORE,BlockRollbackPreviewPlan.Outcome.STATE_MISMATCH),rows.map{plan.observe(it,state("gold_block"))})
        assertEquals(3,db.lookupBlocks(BlockLookupQuery()).size)
    }
    @Test fun resolvedOnlineUuidAndOfflineNamesRemainIndependentForEachUser()=stores { db->
        val renamed=alice.copy(lastKnownName="Renamed")
        db.append(listOf(row(1,renamed),row(2,ActorIdentity.Player(UUID.randomUUID(),"Alice")),row(3,carol),row(4,ActorIdentity.Unknown)))
        val q=BlockLookupQuery(search=search("u:Alice,carol"),rollbackStates=BlockHistoryOperation.ROLLBACK.eligibleStates)
        assertEquals(listOf(3L,1L),db.lookupBlocks(q).map{it.snapshot.timestampEpochMillis})
        assertEquals(listOf(4L,3L,2L),db.lookupBlocks(q.copy(search=search("u:!Alice"))).map{it.snapshot.timestampEpochMillis})
    }
    @Test fun completionSupportsListsAndResourcesButNotPagingOrMovementRecovery() {
        val resources=listOf("minecraft:stone","example:ore")
        assertEquals(listOf("u:Alice,Bob"),FilterSuggestions.recoveryValues("u:Alice,B",listOf("Alice","Bob"),resources))
        assertEquals(listOf("u:!Bob"),FilterSuggestions.recoveryValues("u:!B",listOf("Bob"),resources))
        assertEquals(listOf("i:minecraft:stone"),FilterSuggestions.recoveryValues("i:sto",emptyList(),resources))
        assertTrue(FilterSuggestions.recoveryValues("",emptyList(),resources).none{it in setOf("l:","p:","o:","m:")})
        assertTrue(FilterSuggestions.recoveryValues("c:",emptyList(),resources).all{it in setOf("c:player","c:worldedit")})
    }
}
