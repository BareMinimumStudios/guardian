package com.bareminimumstudios.guardian.storage.jdbc
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.*
import com.bareminimumstudios.guardian.storage.query.*
import com.bareminimumstudios.guardian.command.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
class EnvironmentHistoryStorageTest {
    private val player=ActorIdentity.Player(UUID.randomUUID(),"Alex")
    private fun row(time:Long,cause:ChangeCause,actor:ActorIdentity=ActorIdentity.Unknown)=BlockChangeSnapshot(time,actor,ResourceId.parse("minecraft:overworld"),BlockPosition(1,64,2),BlockStateSnapshot(ResourceId.parse("minecraft:oak_planks")),BlockStateSnapshot(ResourceId.parse("minecraft:air")),cause,ActionType.BLOCK_BREAK)
    private fun stores(test:(QueryableStorageBackend)->Unit) {
        listOf<QueryableStorageBackend>(InMemoryStorageBackend(),SqliteStorageBackend(Files.createTempDirectory("hazard-sqlite").resolve("audit.db")),DuckDbStorageBackend(Files.createTempDirectory("hazard-duck").resolve("audit.db"))).forEach{it.use{db->db.open();test(db)}}
    }
    @Test fun causeFiltersRunBeforePaginationAndKeepUnknownActorsSeparate()=stores { db->
        val a=row(1,ChangeCause.FIRE);val b=row(2,ChangeCause.EXPLOSION,player);val c=row(3,ChangeCause.PLAYER,player)
        db.append(listOf(a,b,c));assertEquals(listOf(a),db.lookupBlocks(BlockLookupQuery(causes=setOf(ChangeCause.FIRE),limit=1)).map{it.snapshot})
        assertEquals(listOf(b),db.lookupBlocks(BlockLookupQuery(causes=setOf(ChangeCause.FIRE,ChangeCause.EXPLOSION),limit=1)).map{it.snapshot})
        assertTrue(db.lookupBlocks(BlockLookupQuery(causes=setOf(ChangeCause.FIRE),actorUuid=player.uuid)).isEmpty())
        assertEquals(listOf(b),db.lookupBlocks(BlockLookupQuery(causes=setOf(ChangeCause.EXPLOSION),search=HistorySearchFilter(players=listOf(HistoryPlayer("Alex"))))).map{it.snapshot})
    }
    @Test fun environmentalRowsReusePurgeAndRollbackFlags()=stores { db->
        db.append(listOf(row(1,ChangeCause.FIRE),row(2,ChangeCause.EXPLOSION)))
        val first=db.lookupBlocks(BlockLookupQuery(causes=setOf(ChangeCause.FIRE))).single()
        db.setBlockRollbackState(listOf(first.rowId),BlockRollbackState.ROLLED_BACK)
        assertEquals(BlockRollbackState.ROLLED_BACK,db.lookupBlocks(BlockLookupQuery(causes=setOf(ChangeCause.FIRE))).single().rollbackState)
        assertEquals(2,db.applyPurge(db.previewPurge(3)){true}.deleted.blocks)
    }
    @Test fun causeAndPayloadSurviveReopenWithNoSchemaChange() {
        for(duck in listOf(false,true)) {
            val path=Files.createTempDirectory("hazard-restart").resolve("audit.db");fun db():QueryableStorageBackend=if(duck)DuckDbStorageBackend(path)else SqliteStorageBackend(path)
            val event=row(1,ChangeCause.EXPLOSION,player)
            db().use{it.open();it.append(listOf(event))};db().use{it.open();assertEquals(21,it.health().schemaVersion);assertEquals(listOf(event),it.lookupBlocks(BlockLookupQuery(causes=setOf(ChangeCause.EXPLOSION))).map{r->r.snapshot})}
        }
    }
    @Test fun causeParserAndSuggestionsRejectMistakesAndNonBlockCommands() {
        assertEquals(setOf(ChangeCause.FIRE,ChangeCause.EXPLOSION),BlockCommandFilterParser.parse("c:fire,explosion").getOrThrow().causes)
        assertTrue(BlockCommandFilterParser.parse("c:fire cause:explosion").isFailure);assertTrue(BlockCommandFilterParser.parse("c:invented").isFailure)
        assertEquals(listOf("c:fire"),FilterSuggestions.values("c:f",emptyList()))
        assertTrue(EntityCommandFilters.parse("c:fire").isFailure);assertTrue(FluidCommandFilters.parse("c:fire").isFailure)
    }
}
