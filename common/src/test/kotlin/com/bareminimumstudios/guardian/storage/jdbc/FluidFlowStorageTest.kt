package com.bareminimumstudios.guardian.storage.jdbc
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.*
import com.bareminimumstudios.guardian.storage.purge.PurgeCounts
import com.bareminimumstudios.guardian.storage.query.*
import com.bareminimumstudios.guardian.command.FluidCommandFilters
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
class FluidFlowStorageTest {
    private val water=FluidSnapshot(ResourceId.parse("minecraft:flowing_water"),false,ResourceId.parse("minecraft:water"),7)
    private val empty=FluidSnapshot(FluidSnapshot.EMPTY,false,ResourceId.parse("minecraft:air"),0)
    private val actor=ActorIdentity.Player(UUID.randomUUID(),"Alex")
    private fun row()=FluidAuditEvent(timestampEpochMillis=1,actor=ActorIdentity.Unknown,dimension=ResourceId.parse("minecraft:overworld"),position=BlockPosition(15,64,0),kind=FluidAuditKind.FLOW,before=empty,after=water)
    private fun stores(test:(QueryableStorageBackend)->Unit) {
        listOf<QueryableStorageBackend>(InMemoryStorageBackend(),SqliteStorageBackend(Files.createTempDirectory("flow-sqlite").resolve("audit.db")),DuckDbStorageBackend(Files.createTempDirectory("flow-duck").resolve("audit.db"))).forEach{it.use{db->db.open();test(db)}}
    }
    @Test fun levelsUnknownAttributionAndFormationRoundTrip()=stores { db->
        val flow=row();val level=row().copy(timestampEpochMillis=2,before=water,after=water.copy(amount=6),actor=actor)
        val form=row().copy(timestampEpochMillis=3,kind=FluidAuditKind.FORM,before=water,after=empty.copy(blockId=ResourceId.parse("minecraft:stone")))
        db.append(listOf(flow,level,form,flow));assertEquals(listOf(form,level,flow),db.lookupFluids(FluidLookupQuery()))
        assertEquals(ActionType.FLUID_FLOW,level.action);assertEquals(ActionType.FLUID_FORM,form.action)
        assertEquals(PurgeCounts(entities=0,fluids=3),db.previewPurge(4).eligible);assertEquals(3,db.applyPurge(db.previewPurge(4)){true}.deleted.fluids)
    }
    @Test fun unknownActorDoesNotMatchPlayerAndNewKindsFilterBeforePaging()=stores { db->
        val a=row();val b=row().copy(actor=actor,timestampEpochMillis=2);db.append(listOf(a,b))
        assertEquals(listOf(b),db.lookupFluids(FluidLookupQuery(search=HistorySearchFilter(players=listOf(HistoryPlayer("Alex"))))))
        assertTrue(db.lookupFluids(FluidLookupQuery(kinds=setOf(FluidAuditKind.PLACE))).isEmpty())
        assertEquals(listOf(a),db.lookupFluids(FluidLookupQuery(kinds=setOf(FluidAuditKind.FLOW),limit=1,oldestFirst=true)))
    }
    @Test fun invalidEmptyLevelsAndNoOpRecordsAreRejected() {
        assertFailsWith<IllegalArgumentException>{empty.copy(amount=1)};assertFailsWith<IllegalArgumentException>{water.copy(amount=9)}
        assertFailsWith<IllegalArgumentException>{row().copy(before=water)}
        assertFailsWith<IllegalArgumentException>{row().copy(kind=FluidAuditKind.PLACE)}
        assertFailsWith<IllegalArgumentException>{row().copy(kind=FluidAuditKind.FORM)}
    }
    @Test fun oldSchemaNineteenRowsKeepUnknownAmountsAcrossUpgradeAndRestart() {
        for(duck in listOf(false,true)) {
            val path=Files.createTempDirectory("flow-upgrade").resolve("audit.db");fun db():QueryableStorageBackend=if(duck)DuckDbStorageBackend(path)else SqliteStorageBackend(path)
            val old=row().copy(kind=FluidAuditKind.PLACE,actor=actor,before=empty.copy(amount=null),after=water.copy(amount=null))
            db().use{it.open();it.append(listOf(old))}
            DriverManager.getConnection((if(duck)"jdbc:duckdb:"else "jdbc:sqlite:")+path).use{c->c.createStatement().use{s->
                listOf("ex_fluid_time_idx","ex_fluid_actor_idx","ex_fluid_location_idx").forEach { s.executeUpdate("DROP INDEX $it") };s.executeUpdate("ALTER TABLE ex_fluid DROP COLUMN before_amount");s.executeUpdate("ALTER TABLE ex_fluid DROP COLUMN after_amount");GuardianSchema.migrations.single { it.version==18 }.statements.filter { it.startsWith("CREATE INDEX") }.forEach(s::executeUpdate)
                s.executeUpdate("DELETE FROM ex_schema_migrations WHERE version>=20");com.bareminimumstudios.guardian.storage.jdbc.LegacyMovementSchema.strip(s);s.executeUpdate("UPDATE ex_meta SET meta_value='19' WHERE meta_key='schema_version'")
            }}
            db().use{it.open();assertEquals(com.bareminimumstudios.guardian.storage.jdbc.GuardianSchema.CURRENT_VERSION,it.health().schemaVersion);assertEquals(listOf(old),it.lookupFluids(FluidLookupQuery()));it.append(listOf(row()))}
            db().use{it.open();assertEquals(2,it.lookupFluids(FluidLookupQuery()).size)}
        }
    }
    @Test fun actionParserRecognizesNaturalKinds() { assertEquals(setOf(FluidAuditKind.FLOW,FluidAuditKind.FORM),FluidCommandFilters.parse("a:flow,form").getOrThrow().kinds) }
}
