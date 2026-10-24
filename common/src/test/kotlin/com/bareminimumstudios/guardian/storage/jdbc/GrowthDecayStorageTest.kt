package com.bareminimumstudios.guardian.storage.jdbc
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.*
import com.bareminimumstudios.guardian.storage.query.*
import com.bareminimumstudios.guardian.command.*
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
class GrowthDecayStorageTest {
    private val player=ActorIdentity.Player(UUID.randomUUID(),"Alex")
    private fun row(time:Long,cause:ChangeCause=ChangeCause.GROWTH,actor:ActorIdentity=ActorIdentity.Unknown)=BlockChangeSnapshot(time,actor,ResourceId.parse("minecraft:overworld"),BlockPosition(1,64,2),BlockStateSnapshot(ResourceId.parse("minecraft:wheat"),mapOf("age" to "0")),BlockStateSnapshot(ResourceId.parse("minecraft:wheat"),mapOf("age" to "1")),cause,ActionType.BLOCK_CHANGE)
    private fun stores(test:(QueryableStorageBackend)->Unit) {
        listOf<QueryableStorageBackend>(InMemoryStorageBackend(),SqliteStorageBackend(Files.createTempDirectory("growth-sqlite").resolve("audit.db")),DuckDbStorageBackend(Files.createTempDirectory("growth-duck").resolve("audit.db"))).forEach{it.use{db->db.open();test(db)}}
    }
    @Test fun naturalAndBoneMealActorsStaySeparateBeforePaging()=stores { db->
        val natural=row(1);val meal=row(2,actor=player);val decay=row(3,ChangeCause.DECAY).copy(before=BlockStateSnapshot(ResourceId.parse("minecraft:oak_leaves")),after=BlockStateSnapshot(ResourceId.parse("minecraft:air")),action=ActionType.BLOCK_BREAK)
        db.append(listOf(natural,meal,decay));assertEquals(listOf(meal),db.lookupBlocks(BlockLookupQuery(causes=setOf(ChangeCause.GROWTH),limit=1)).map{it.snapshot})
        assertEquals(listOf(meal),db.lookupBlocks(BlockLookupQuery(causes=setOf(ChangeCause.GROWTH),search=HistorySearchFilter(players=listOf(HistoryPlayer("Alex"))))).map{it.snapshot})
        assertTrue(db.lookupBlocks(BlockLookupQuery(causes=setOf(ChangeCause.DECAY),actorUuid=player.uuid)).isEmpty())
        assertEquals(listOf(decay),db.lookupBlocks(BlockLookupQuery(causes=setOf(ChangeCause.DECAY))).map{it.snapshot})
    }
    @Test fun growthAndDecayReuseConfirmedBlockPurge()=stores { db->
        db.append(listOf(row(1),row(2,ChangeCause.DECAY),row(3)))
        assertEquals(2,db.previewPurge(3).eligible.blocks);assertEquals(2,db.applyPurge(db.previewPurge(3)){true}.deleted.blocks)
        assertEquals(listOf(3L),db.lookupBlocks(BlockLookupQuery()).map{it.snapshot.timestampEpochMillis})
    }
    @Test fun schema20UpgradePreservesOldHistoryAndNewCausesSurviveRestart() {
        for(duck in listOf(false,true)) {
            val path=Files.createTempDirectory("growth-upgrade").resolve("audit.db");fun db():QueryableStorageBackend=if(duck)DuckDbStorageBackend(path)else SqliteStorageBackend(path)
            val old=row(1,ChangeCause.PLAYER,player);val grown=row(2)
            db().use{it.open();it.append(listOf(old))}
            DriverManager.getConnection((if(duck)"jdbc:duckdb:"else "jdbc:sqlite:")+path).use{c->c.createStatement().use{s->s.executeUpdate("DELETE FROM ex_schema_migrations WHERE version=21");s.executeUpdate("UPDATE ex_meta SET meta_value='20' WHERE meta_key='schema_version'")}}
            db().use{it.open();assertEquals(21,it.health().schemaVersion);assertEquals(listOf(old),it.lookupBlocks(BlockLookupQuery()).map{r->r.snapshot});it.append(listOf(grown))}
            db().use{it.open();assertEquals(listOf(grown,old),it.lookupBlocks(BlockLookupQuery()).map{r->r.snapshot})}
        }
    }
    @Test fun causeCodesParserAndSuggestionsAreDistinct() {
        assertEquals(14,ChangeCause.GROWTH.storageCode);assertEquals(15,ChangeCause.DECAY.storageCode)
        assertEquals(setOf(ChangeCause.GROWTH,ChangeCause.DECAY),BlockCommandFilterParser.parse("c:growth,decay").getOrThrow().causes)
        assertEquals(listOf("c:growth"),FilterSuggestions.values("c:g",emptyList()))
        assertTrue(FluidCommandFilters.parse("c:growth").isFailure);assertTrue(EntityCommandFilters.parse("c:decay").isFailure)
    }
}
