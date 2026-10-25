package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.*
import com.bareminimumstudios.guardian.storage.query.*
import com.bareminimumstudios.guardian.command.FluidCommandFilters
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test

class FluidHistoryStorageTest {
    private val alex=ActorIdentity.Player(UUID.randomUUID(),"Alex")
    private val empty=FluidSnapshot(FluidSnapshot.EMPTY,false,ResourceId.parse("minecraft:air"))
    private val water=FluidSnapshot(ResourceId.parse("minecraft:water"),true,ResourceId.parse("minecraft:water"))
    private fun row(time:Long=1)=FluidAuditEvent(timestampEpochMillis=time,actor=alex,dimension=ResourceId.parse("minecraft:overworld"),position=BlockPosition(1,64,2),kind=FluidAuditKind.PLACE,before=empty,after=water)
    private fun stores(test:(QueryableStorageBackend)->Unit) {
        listOf<QueryableStorageBackend>(InMemoryStorageBackend(),SqliteStorageBackend(Files.createTempDirectory("fluid-sqlite").resolve("audit.db")),DuckDbStorageBackend(Files.createTempDirectory("fluid-duck").resolve("audit.db"))).forEach { it.use { db->db.open();test(db) } }
    }
    @Test fun eventsAreIdempotentSeparateFromBlocksAndPaged()=stores { db->
        val placed=row();val removed=row(2).copy(kind=FluidAuditKind.REMOVE,before=water,after=empty)
        db.append(listOf(placed,removed,placed))
        assertEquals(listOf(removed,placed),db.lookupFluids(FluidLookupQuery()))
        assertEquals(listOf(placed),db.lookupFluids(FluidLookupQuery(limit=1,offset=1)))
        assertEquals(listOf(placed),db.lookupFluids(FluidLookupQuery(limit=1,oldestFirst=true)))
        assertTrue(db.lookupBlocks(BlockLookupQuery()).isEmpty())
        assertEquals(ActionType.FLUID_PLACE,placed.action);assertEquals(ActionType.FLUID_REMOVE,removed.action)
    }
    @Test fun actorResourceTimeKindAndSpatialPredicatesPrecedePagination()=stores { db->
        val first=row();val other=row(2).copy(actor=ActorIdentity.Player(UUID.randomUUID(),"Bob"),position=BlockPosition(50,64,2))
        db.append(listOf(first,other));val q=FluidLookupQuery(limit=1)
        assertEquals(listOf(first),db.lookupFluids(q.copy(search=HistorySearchFilter(players=listOf(HistoryPlayer("alex"))))))
        assertEquals(listOf(first),db.lookupFluids(q.copy(search=HistorySearchFilter(excludedPlayers=listOf(HistoryPlayer("Bob"))))))
        assertEquals(listOf(other),db.lookupFluids(q.copy(afterEpochMillis=2)))
        assertEquals(listOf(first),db.lookupFluids(q.copy(dimension=first.dimension,position=first.position)))
        assertEquals(listOf(first),db.lookupFluids(q.copy(dimension=first.dimension,bounds=BlockBounds(first.position,first.position))))
        assertEquals(listOf(other),db.lookupFluids(q.copy(search=HistorySearchFilter(included=setOf(water.fluidId)))) )
        assertTrue(db.lookupFluids(q.copy(search=HistorySearchFilter(excluded=setOf(water.fluidId)))).isEmpty())
        assertTrue(db.lookupFluids(q.copy(kinds=setOf(FluidAuditKind.REMOVE))).isEmpty())
    }
    @Test fun waterloggedHostAndRegisteredModFluidIdentityRoundTrip()=stores { db->
        val host=ResourceId.parse("example:grate");val fluid=ResourceId.parse("example:oil")
        val event=row().copy(before=empty.copy(blockId=host),after=water.copy(blockId=host,fluidId=fluid))
        db.append(listOf(event));assertEquals(listOf(event),db.lookupFluids(FluidLookupQuery(search=HistorySearchFilter(included=setOf(fluid)))))
    }
    @Test fun unchangedEmptyPlacementsAndInvalidRemovalAreRefused() {
        assertFailsWith<IllegalArgumentException>{row().copy(after=empty)}
        assertFailsWith<IllegalArgumentException>{row().copy(kind=FluidAuditKind.REMOVE)}
        assertFailsWith<IllegalArgumentException>{row().copy(before=water)}
        assertFailsWith<IllegalArgumentException>{row().copy(timestampEpochMillis=-1)}
        assertFailsWith<IllegalArgumentException>{empty.copy(source=true)}
        assertFailsWith<IllegalArgumentException>{row().copy(after=empty.copy(blockId=water.blockId))}
    }
    @Test fun actionsAndCommonFiltersRemainDistinctFromEntityAndBlockActions() {
        assertEquals(setOf(FluidAuditKind.PLACE),FluidCommandFilters.parse("a:place u:Alex t:1h r:5").getOrThrow().kinds)
        assertEquals(FluidAuditKind.entries.toSet(),FluidCommandFilters.parse("a:place,remove,flow,form").getOrThrow().kinds)
        assertTrue(FluidCommandFilters.parse("a:death").isFailure);assertTrue(FluidCommandFilters.parse("a:place a:remove").isFailure)
    }
    @Test fun schema17UpgradePreservesEntityHistoryAndFluidsSurviveRestart() {
        for(duck in listOf(false,true)) {
            val file=Files.createTempDirectory("fluid-upgrade").resolve("audit.db");fun db():QueryableStorageBackend=if(duck)DuckDbStorageBackend(file)else SqliteStorageBackend(file)
            val entity=EntityAuditEvent(timestampEpochMillis=1,actor=alex,dimension=row().dimension,position=row().position,targetUuid=UUID.randomUUID(),targetType=ResourceId.parse("minecraft:pig"),kind=EntityAuditKind.DEATH)
            db().use{it.open();it.append(listOf(entity))}
            DriverManager.getConnection((if(duck)"jdbc:duckdb:"else "jdbc:sqlite:")+file).use { conn->conn.createStatement().use { s->
                listOf("ex_entity_time_idx","ex_entity_target_idx","ex_entity_location_idx","ex_entity_actor_idx").forEach { s.executeUpdate("DROP INDEX $it") }
                s.executeUpdate("ALTER TABLE ex_entity DROP COLUMN frame_before");s.executeUpdate("ALTER TABLE ex_entity DROP COLUMN frame_after")
                GuardianSchema.migrations.single { it.version==16 }.statements.filter { it.startsWith("CREATE INDEX") }.forEach(s::executeUpdate)
                s.executeUpdate("DROP TABLE ex_fluid");s.executeUpdate("DELETE FROM ex_schema_migrations WHERE version>=18");com.bareminimumstudios.guardian.storage.jdbc.LegacyMovementSchema.strip(s);s.executeUpdate("UPDATE ex_meta SET meta_value='17' WHERE meta_key='schema_version'")
            } }
            val placed=row();db().use{it.open();assertEquals(23,it.health().schemaVersion);assertEquals(listOf(entity),it.lookupEntities(EntityLookupQuery()));it.append(listOf(placed))}
            db().use{it.open();assertEquals(listOf(placed),it.lookupFluids(FluidLookupQuery()))}
        }
    }
    @Test fun purgeIncludesFluidHistory()=stores { db->
        val placed=row();db.append(listOf(placed));assertEquals(1,db.applyPurge(db.previewPurge(100)){true}.deleted.fluids);assertTrue(db.lookupFluids(FluidLookupQuery()).isEmpty())
    }
}
