package com.bareminimumstudios.guardian.storage.jdbc
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.*
import com.bareminimumstudios.guardian.storage.query.*
import com.bareminimumstudios.guardian.storage.purge.PurgeScope
import com.bareminimumstudios.guardian.command.*
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
class SignHistoryStorageTest {
    private val player=ActorIdentity.Player(UUID.randomUUID(),"Alice")
    private fun row(time:Long)=BlockChangeSnapshot(time,player,ResourceId.parse("minecraft:overworld"),BlockPosition(15,70,16),BlockStateSnapshot(ResourceId.parse("minecraft:oak_sign"),mapOf("rotation" to "0"),BinaryPayload.of(byteArrayOf(1,2,3))),BlockStateSnapshot(ResourceId.parse("minecraft:oak_sign"),mapOf("rotation" to "0"),BinaryPayload.of(byteArrayOf(4,5,6))),ChangeCause.SIGN_TEXT,ActionType.BLOCK_CHANGE)
    private fun stores(test:(QueryableStorageBackend)->Unit) {
        listOf<QueryableStorageBackend>(InMemoryStorageBackend(),SqliteStorageBackend(Files.createTempDirectory("sign-sqlite").resolve("audit.db")),DuckDbStorageBackend(Files.createTempDirectory("sign-duck").resolve("audit.db"))).forEach{it.use{db->db.open();test(db)}}
    }
    @Test fun signPayloadAndActorFiltersPrecedePaging()=stores { db->
        val a=row(1);val b=row(2);val other=row(3).copy(cause=ChangeCause.PLAYER)
        db.append(listOf(a,b,other));val q=BlockLookupQuery(causes=setOf(ChangeCause.SIGN_TEXT),search=HistorySearchFilter(players=listOf(HistoryPlayer("Alice")),included=setOf(ResourceId.parse("oak_sign"))),limit=1)
        assertEquals(listOf(b),db.lookupBlocks(q).map{it.snapshot});assertEquals(listOf(a),db.lookupBlocks(q.copy(offset=1)).map{it.snapshot})
        assertTrue(db.lookupBlocks(q.copy(search=HistorySearchFilter(excludedPlayers=listOf(HistoryPlayer("Alice"))))).isEmpty())
    }
    @Test fun signHistoryUsesProtectedScopedBlockPurge()=stores { db->
        db.append(listOf(row(1),row(100)))
        val plan=db.previewPurge(100,1000,PurgeScope(include=setOf(ResourceId.parse("oak_sign"))))
        assertEquals(1,plan.eligible.blocks);assertEquals(1,db.applyPurge(plan){true}.deleted.blocks)
        assertEquals(listOf(100L),db.lookupBlocks(BlockLookupQuery()).map{it.snapshot.timestampEpochMillis})
    }
    @Test fun schema22UpgradeRetainsHistoryAndNewSignPayloadSurvivesRestart() {
        for(duck in listOf(false,true)) {
            val path=Files.createTempDirectory("sign-upgrade").resolve("audit.db");fun db():QueryableStorageBackend=if(duck)DuckDbStorageBackend(path)else SqliteStorageBackend(path)
            val old=row(1).copy(cause=ChangeCause.PLAYER)
            db().use{it.open();it.append(listOf(old))}
            DriverManager.getConnection((if(duck)"jdbc:duckdb:"else "jdbc:sqlite:")+path).use{c->c.createStatement().use{s->LegacyMovementSchema.stripPrivateActivity(s);s.executeUpdate("DROP TABLE ex_container_entity");s.executeUpdate("DELETE FROM ex_schema_migrations WHERE version>=23");s.executeUpdate("UPDATE ex_meta SET meta_value='22' WHERE meta_key='schema_version'")}}
            db().use{it.open();assertEquals(GuardianSchema.CURRENT_VERSION,it.health().schemaVersion);assertEquals(listOf(old),it.lookupBlocks(BlockLookupQuery()).map{r->r.snapshot});it.append(listOf(row(2)))}
            db().use{it.open();assertEquals(listOf(2L),it.lookupBlocks(BlockLookupQuery(causes=setOf(ChangeCause.SIGN_TEXT))).map{r->r.snapshot.timestampEpochMillis})}
        }
    }
    @Test fun signCauseAndHistoryCompletionAreDistinctFromRecovery() {
        assertEquals(17,ChangeCause.SIGN_TEXT.storageCode);assertEquals(ChangeCause.SIGN_TEXT,ChangeCause.fromStorageCode(17))
        assertEquals(setOf(ChangeCause.SIGN_TEXT),BlockCommandFilterParser.parse("c:sign").getOrThrow().causes)
        assertEquals(listOf("c:sign"),FilterSuggestions.values("c:s",emptyList()))
        assertTrue(FilterSuggestions.recoveryValues("c:s",emptyList(),emptyList()).isEmpty())
        assertTrue(EntityCommandFilters.parse("c:sign").isFailure);assertTrue(FluidCommandFilters.parse("c:sign").isFailure)
    }
}
