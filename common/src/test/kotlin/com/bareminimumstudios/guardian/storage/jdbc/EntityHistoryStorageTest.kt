package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.*
import com.bareminimumstudios.guardian.storage.query.*
import com.bareminimumstudios.guardian.command.EntityCommandFilters
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test

class EntityHistoryStorageTest {
    private val alex=ActorIdentity.Player(UUID.randomUUID(),"Alex")
    private val dimension=ResourceId.parse("minecraft:overworld")
    private val target=UUID.randomUUID()
    private fun row(time: Long,kind: EntityAuditKind=EntityAuditKind.DEATH)=EntityAuditEvent(timestampEpochMillis=time,actor=alex,dimension=dimension,position=BlockPosition(1,64,2),targetUuid=target,targetType=ResourceId.parse("example:deer"),kind=kind,beforeName="Old",afterName=if(kind==EntityAuditKind.NAME_CHANGE)"New" else null,damageType=if(kind==EntityAuditKind.DEATH)ResourceId.parse("minecraft:fall") else null)
    private fun stores(test:(QueryableStorageBackend)->Unit) {
        listOf<QueryableStorageBackend>(InMemoryStorageBackend(),SqliteStorageBackend(Files.createTempDirectory("entity-sqlite").resolve("audit.db")),DuckDbStorageBackend(Files.createTempDirectory("entity-duck").resolve("audit.db"))).forEach { it.use { db->db.open();test(db) } }
    }
    @Test fun deathsAndRenamesAreIndependentIdempotentAndPaged()=stores { db->
        val death=row(1);val rename=row(2,EntityAuditKind.NAME_CHANGE)
        db.append(listOf(death,rename,death))
        val q=EntityLookupQuery(limit=1)
        assertEquals(listOf(rename),db.lookupEntities(q));assertEquals(listOf(death),db.lookupEntities(q.copy(offset=1)))
        assertEquals(listOf(death),db.lookupEntities(q.copy(oldestFirst=true)))
        assertEquals(listOf(death),db.lookupEntities(q.copy(kinds=setOf(EntityAuditKind.DEATH))))
        assertEquals(emptyList(),db.lookupActivity(PlayerActivityQuery(setOf(PlayerActivityKind.CHAT))))
        assertEquals(ActionType.ENTITY_DEATH,death.action);assertEquals(ActionType.ENTITY_NAME_CHANGE,rename.action)
    }
    @Test fun actorTypeTargetTimeAndLocationFiltersRunBeforePagination()=stores { db->
        val first=row(1);val other=row(2).copy(actor=ActorIdentity.System("environment"),targetUuid=UUID.randomUUID(),position=BlockPosition(100,64,2))
        val nether=row(3).copy(dimension=ResourceId.parse("minecraft:the_nether"))
        db.append(listOf(first,other,nether))
        val q=EntityLookupQuery(dimension=dimension,limit=1)
        assertEquals(listOf(first),db.lookupEntities(q.copy(search=HistorySearchFilter(players=listOf(HistoryPlayer("alex"))))))
        assertEquals(listOf(other),db.lookupEntities(q.copy(search=HistorySearchFilter(excludedPlayers=listOf(HistoryPlayer("Alex"))))))
        assertEquals(listOf(first),db.lookupEntities(q.copy(targetUuid=target)))
        assertEquals(listOf(other),db.lookupEntities(q.copy(afterEpochMillis=2)))
        assertEquals(listOf(first),db.lookupEntities(q.copy(position=BlockPosition(0,64,2),radius=1)))
        assertEquals(listOf(first),db.lookupEntities(q.copy(bounds=BlockBounds(BlockPosition(1,64,2),BlockPosition(1,64,2)))))
        assertEquals(emptyList(),db.lookupEntities(q.copy(search=HistorySearchFilter(excluded=setOf(first.targetType)))))
        assertEquals(listOf(other),db.lookupEntities(q.copy(search=HistorySearchFilter(included=setOf(first.targetType)))))
    }
    @Test fun unknownAndEntityActorsRoundTripWithoutInventedPlayers()=stores { db->
        val unknown=row(1).copy(actor=ActorIdentity.Unknown)
        val mob=row(2).copy(actor=ActorIdentity.Entity(UUID.randomUUID(),ResourceId.parse("example:bear")))
        db.append(listOf(unknown,mob));assertEquals(listOf(mob,unknown),db.lookupEntities(EntityLookupQuery()))
    }
    @Test fun immutableNameBoundsAndEventShapesAreValidated() {
        assertFailsWith<IllegalArgumentException>{row(1).copy(beforeName="a".repeat(257))}
        assertFailsWith<IllegalArgumentException>{row(1).copy(beforeName="bad\nname")}
        assertFailsWith<IllegalArgumentException>{row(1).copy(afterName="wrong")}
        assertFailsWith<IllegalArgumentException>{row(1,EntityAuditKind.NAME_CHANGE).copy(afterName="Old")}
        assertFailsWith<IllegalArgumentException>{row(1,EntityAuditKind.NAME_CHANGE).copy(damageType=ResourceId.parse("minecraft:fall"))}
        assertFailsWith<IllegalArgumentException>{row(-1)}
    }
    @Test fun entityFiltersParseStandardSearchAndDistinctActions() {
        val f=EntityCommandFilters.parse("u:Alex t:1h a:death,rename i:example:deer id:$target p:2").getOrThrow()
        assertEquals(target,f.targetUuid);assertEquals(EntityAuditKind.entries.toSet(),f.kinds);assertEquals(2,f.common.page)
        assertTrue(EntityCommandFilters.parse("a:place").isFailure)
        assertTrue(EntityCommandFilters.parse("a:death a:rename").isFailure)
        assertTrue(EntityCommandFilters.parse("id:not-a-uuid").isFailure)
        assertTrue(EntityCommandFilters.parse("id:$target id:$target").isFailure)
    }
    @Test fun schema15UpgradePreservesExistingHistoryAndEntityRowsSurviveRestart() {
        for(duck in listOf(false,true)) {
            val file=Files.createTempDirectory("entity-upgrade").resolve("audit.db");fun db():QueryableStorageBackend=if(duck)DuckDbStorageBackend(file)else SqliteStorageBackend(file)
            val chat=PlayerActivity(timestampEpochMillis=1,actor=alex,kind=PlayerActivityKind.CHAT,text="kept")
            db().use{it.open();it.append(listOf(chat))}
            DriverManager.getConnection((if(duck)"jdbc:duckdb:"else "jdbc:sqlite:")+file).use { conn->conn.createStatement().use { s->s.executeUpdate("DROP TABLE ex_entity");s.executeUpdate("DELETE FROM ex_schema_migrations WHERE version=16");s.executeUpdate("UPDATE ex_meta SET meta_value='15' WHERE meta_key='schema_version'") } }
            val death=row(2).copy(beforeName="quote ' SQL -- ☃")
            db().use{it.open();assertEquals(GuardianSchema.CURRENT_VERSION,it.health().schemaVersion);assertEquals(listOf(chat),it.lookupActivity(PlayerActivityQuery(setOf(PlayerActivityKind.CHAT))));it.append(listOf(death))}
            db().use{it.open();assertEquals(listOf(death),it.lookupEntities(EntityLookupQuery()))}
        }
    }
    @Test fun existingPurgeDoesNotDeleteEntityHistory()=stores { db->
        val death=row(1);db.append(listOf(death));val plan=db.previewPurge(100);db.applyPurge(plan){true}
        assertEquals(listOf(death),db.lookupEntities(EntityLookupQuery()))
    }
    @Test fun invalidQueriesRejectAmbiguousSpatialScopesAndUnboundedPages() {
        assertFailsWith<IllegalArgumentException>{EntityLookupQuery(limit=501)}
        assertFailsWith<IllegalArgumentException>{EntityLookupQuery(offset=-1)}
        assertFailsWith<IllegalArgumentException>{EntityLookupQuery(kinds=emptySet())}
        assertFailsWith<IllegalArgumentException>{EntityLookupQuery(position=BlockPosition(0,0,0))}
        assertFailsWith<IllegalArgumentException>{EntityLookupQuery(dimension=dimension,radius=2)}
    }
}
