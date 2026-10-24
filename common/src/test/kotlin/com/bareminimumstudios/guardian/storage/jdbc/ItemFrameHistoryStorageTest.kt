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

class ItemFrameHistoryStorageTest {
    private val actor=ActorIdentity.Player(UUID.randomUUID(),"Alex")
    private val empty=ItemFrameSnapshot(ItemStackSnapshot.EMPTY,0)
    private val full=ItemFrameSnapshot(ItemStackSnapshot(ResourceId.parse("example:relic"),1,BinaryPayload.of(byteArrayOf(1,2,3))),0)
    private fun event(kind:EntityAuditKind=EntityAuditKind.FRAME_PLACE)=EntityAuditEvent(timestampEpochMillis=1,actor=actor,dimension=ResourceId.parse("minecraft:overworld"),position=BlockPosition(1,64,2),targetUuid=UUID.randomUUID(),targetType=ResourceId.parse("minecraft:item_frame"),kind=kind,frameBefore=if(kind==EntityAuditKind.FRAME_PLACE)null else empty,frameAfter=if(kind==EntityAuditKind.FRAME_REMOVE)null else full)
    private fun stores(test:(QueryableStorageBackend)->Unit) {
        listOf<QueryableStorageBackend>(InMemoryStorageBackend(),SqliteStorageBackend(Files.createTempDirectory("frame-sqlite").resolve("audit.db")),DuckDbStorageBackend(Files.createTempDirectory("frame-duck").resolve("audit.db"))).forEach { it.use { db->db.open();test(db) } }
    }
    @Test fun framesRoundTripComponentsRotationAndNullBoundariesAcrossKinds()=stores { db->
        val placed=event();val changed=event(EntityAuditKind.FRAME_CHANGE).copy(timestampEpochMillis=2);val removed=event(EntityAuditKind.FRAME_REMOVE).copy(timestampEpochMillis=3,frameBefore=full.copy(rotation=7))
        db.append(listOf(placed,changed,removed,placed))
        assertEquals(listOf(removed,changed,placed),db.lookupEntities(EntityLookupQuery()))
        assertEquals(listOf(changed),db.lookupEntities(EntityLookupQuery(kinds=setOf(EntityAuditKind.FRAME_CHANGE))))
        assertEquals(listOf(placed),db.lookupEntities(EntityLookupQuery(targetUuid=placed.targetUuid)))
        assertEquals(listOf(changed),db.lookupEntities(EntityLookupQuery(limit=1,offset=1)))
        assertTrue(db.lookupContainers(ContainerLookupQuery()).isEmpty());assertTrue(db.lookupBlocks(BlockLookupQuery()).isEmpty())
    }
    @Test fun framePayloadsRejectUnchangedMissingAndWrongKindSnapshots() {
        assertFailsWith<IllegalArgumentException>{event().copy(frameAfter=null)}
        assertFailsWith<IllegalArgumentException>{event().copy(frameBefore=empty)}
        assertFailsWith<IllegalArgumentException>{event(EntityAuditKind.FRAME_CHANGE).copy(frameAfter=empty)}
        assertFailsWith<IllegalArgumentException>{event().copy(kind=EntityAuditKind.DEATH)}
        assertFailsWith<IllegalArgumentException>{empty.copy(rotation=8)}
        assertFailsWith<IllegalArgumentException>{full.copy(item=full.item.copy(count=2))}
    }
    @Test fun frameGroupActionsPreserveStandardUserAndEntityFilters() {
        val parsed=EntityCommandFilters.parse("a:frame u:Alex i:minecraft:item_frame t:1h").getOrThrow()
        assertEquals(setOf(EntityAuditKind.FRAME_PLACE,EntityAuditKind.FRAME_REMOVE,EntityAuditKind.FRAME_CHANGE),parsed.kinds)
        assertEquals(setOf(EntityAuditKind.FRAME_REMOVE),EntityCommandFilters.parse("a:frame-remove").getOrThrow().kinds)
        assertEquals(setOf(EntityAuditKind.DEATH,EntityAuditKind.FRAME_CHANGE),EntityCommandFilters.parse("a:death,frame-change").getOrThrow().kinds)
    }
    @Test fun schema18UpgradeKeepsPriorEventsAndFrameDataSurvivesRestart() {
        for(duck in listOf(false,true)) {
            val path=Files.createTempDirectory("frame-upgrade").resolve("audit.db");fun db():QueryableStorageBackend=if(duck)DuckDbStorageBackend(path)else SqliteStorageBackend(path)
            val prior=event().copy(kind=EntityAuditKind.DEATH,frameAfter=null)
            db().use{it.open();it.append(listOf(prior))}
            DriverManager.getConnection((if(duck)"jdbc:duckdb:"else "jdbc:sqlite:")+path).use { conn->conn.createStatement().use { s->
                listOf("ex_entity_time_idx","ex_entity_target_idx","ex_entity_location_idx","ex_entity_actor_idx").forEach { s.executeUpdate("DROP INDEX $it") }
                s.executeUpdate("ALTER TABLE ex_entity DROP COLUMN frame_before");s.executeUpdate("ALTER TABLE ex_entity DROP COLUMN frame_after")
                GuardianSchema.migrations.single { it.version==16 }.statements.filter { it.startsWith("CREATE INDEX") }.forEach(s::executeUpdate)
                s.executeUpdate("DELETE FROM ex_schema_migrations WHERE version=19");s.executeUpdate("UPDATE ex_meta SET meta_value='18' WHERE meta_key='schema_version'")
            } }
            val frame=event().copy(timestampEpochMillis=2)
            db().use{it.open();assertEquals(19,it.health().schemaVersion);assertEquals(listOf(prior),it.lookupEntities(EntityLookupQuery()));it.append(listOf(frame))}
            db().use{it.open();assertEquals(listOf(frame,prior),it.lookupEntities(EntityLookupQuery()))}
        }
    }
    @Test fun purgeIncludesFrameHistory()=stores { db->
        val row=event();db.append(listOf(row));assertEquals(1,db.applyPurge(db.previewPurge(100)){true}.deleted.entities);assertTrue(db.lookupEntities(EntityLookupQuery()).isEmpty())
    }
}
