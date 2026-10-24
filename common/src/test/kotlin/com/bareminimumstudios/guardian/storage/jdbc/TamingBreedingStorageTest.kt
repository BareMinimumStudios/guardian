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

class TamingBreedingStorageTest {
    private val player=ActorIdentity.Player(UUID.randomUUID(),"Alex")
    private fun event(kind: EntityAuditKind)=EntityAuditEvent(timestampEpochMillis=1000,actor=player,dimension=ResourceId.parse("minecraft:overworld"),position=BlockPosition(1,64,2),targetUuid=UUID.randomUUID(),targetType=ResourceId.parse("example:deer"),kind=kind,parentUuid=if(kind==EntityAuditKind.BREED) UUID.randomUUID() else null,partnerUuid=if(kind==EntityAuditKind.BREED) UUID.randomUUID() else null)
    private fun stores(test:(QueryableStorageBackend)->Unit) {
        listOf<QueryableStorageBackend>(InMemoryStorageBackend(),SqliteStorageBackend(Files.createTempDirectory("breed-sqlite").resolve("audit.db")),DuckDbStorageBackend(Files.createTempDirectory("breed-duck").resolve("audit.db"))).forEach { it.use { db->db.open();test(db) } }
    }
    @Test fun tamingAndBreedingRoundTripWithDistinctKindsAndParentIdentities()=stores { db->
        val tame=event(EntityAuditKind.TAME);val born=event(EntityAuditKind.BREED).copy(timestampEpochMillis=2000)
        db.append(listOf(tame,born,tame))
        assertEquals(listOf(born,tame),db.lookupEntities(EntityLookupQuery()))
        assertEquals(listOf(tame),db.lookupEntities(EntityLookupQuery(kinds=setOf(EntityAuditKind.TAME))))
        assertEquals(listOf(born),db.lookupEntities(EntityLookupQuery(kinds=setOf(EntityAuditKind.BREED))))
        assertEquals(listOf(born),db.lookupEntities(EntityLookupQuery(targetUuid=born.targetUuid)))
        assertEquals(listOf(born),db.lookupEntities(EntityLookupQuery(search=HistorySearchFilter(players=listOf(HistoryPlayer("alex"))),limit=1)))
        assertEquals(ActionType.ENTITY_TAME,tame.action);assertEquals(ActionType.ENTITY_BREED,born.action)
    }
    @Test fun unknownBreedingCreditDoesNotInventAPlayer()=stores { db->
        val born=event(EntityAuditKind.BREED).copy(actor=ActorIdentity.Unknown);db.append(listOf(born))
        assertEquals(listOf(born),db.lookupEntities(EntityLookupQuery()))
        assertTrue(db.lookupEntities(EntityLookupQuery(search=HistorySearchFilter(players=listOf(HistoryPlayer("Alex"))))).isEmpty())
    }
    @Test fun payloadShapeRefusesMissingOrImpossibleParentsAndTameAttribution() {
        val born=event(EntityAuditKind.BREED);val tame=event(EntityAuditKind.TAME)
        assertFailsWith<IllegalArgumentException>{born.copy(parentUuid=null)}
        assertFailsWith<IllegalArgumentException>{born.copy(partnerUuid=born.parentUuid)}
        assertFailsWith<IllegalArgumentException>{born.copy(parentUuid=born.targetUuid)}
        assertFailsWith<IllegalArgumentException>{tame.copy(parentUuid=UUID.randomUUID())}
        assertFailsWith<IllegalArgumentException>{tame.copy(actor=ActorIdentity.Unknown)}
        assertFailsWith<IllegalArgumentException>{tame.copy(damageType=ResourceId.parse("minecraft:fall"))}
    }
    @Test fun filtersAcceptNewKindsIndependentlyAndTogether() {
        assertEquals(setOf(EntityAuditKind.TAME),EntityCommandFilters.parse("a:tame u:Alex t:1h").getOrThrow().kinds)
        assertEquals(setOf(EntityAuditKind.BREED),EntityCommandFilters.parse("a:breed").getOrThrow().kinds)
        assertEquals(EntityAuditKind.entries.toSet(),EntityCommandFilters.parse("a:death,rename,tame,breed").getOrThrow().kinds)
        assertTrue(EntityCommandFilters.parse("a:breed,spawn").isFailure)
    }
    @Test fun schema16UpgradePreservesOldEntityRowsAndNewParentsAcrossRestart() {
        for(duck in listOf(false,true)) {
            val file=Files.createTempDirectory("breed-upgrade").resolve("audit.db");fun db():QueryableStorageBackend=if(duck)DuckDbStorageBackend(file)else SqliteStorageBackend(file)
            val death=event(EntityAuditKind.DEATH);db().use{it.open();it.append(listOf(death))}
            DriverManager.getConnection((if(duck)"jdbc:duckdb:"else "jdbc:sqlite:")+file).use { conn->conn.createStatement().use { s->
                // DuckDB requires removing dependent indexes before simulating the older table shape.
                listOf("ex_entity_time_idx","ex_entity_target_idx","ex_entity_location_idx","ex_entity_actor_idx").forEach { s.executeUpdate("DROP INDEX $it") }
                s.executeUpdate("ALTER TABLE ex_entity DROP COLUMN parent_uuid");s.executeUpdate("ALTER TABLE ex_entity DROP COLUMN partner_uuid")
                GuardianSchema.migrations.single { it.version==16 }.statements.filter { it.startsWith("CREATE INDEX") }.forEach(s::executeUpdate)
                s.executeUpdate("DELETE FROM ex_schema_migrations WHERE version=17");s.executeUpdate("UPDATE ex_meta SET meta_value='16' WHERE meta_key='schema_version'")
            } }
            val born=event(EntityAuditKind.BREED).copy(timestampEpochMillis=2000)
            db().use{it.open();assertEquals(17,it.health().schemaVersion);assertEquals(listOf(death),it.lookupEntities(EntityLookupQuery()));it.append(listOf(born))}
            db().use{it.open();assertEquals(listOf(born,death),it.lookupEntities(EntityLookupQuery()))}
        }
    }
}
