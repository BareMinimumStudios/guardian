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
class BlockMovementStorageTest {
    private val world=ResourceId.parse("minecraft:overworld")
    private val sand=BlockStateSnapshot(ResourceId.parse("minecraft:sand"));private val air=BlockStateSnapshot(ResourceId.parse("minecraft:air"))
    private fun source(id:UUID)=BlockChangeSnapshot(1,ActorIdentity.Unknown,world,BlockPosition(15,70,0),sand,air,ChangeCause.GRAVITY,ActionType.BLOCK_BREAK,movementId=id,movementPhase=BlockMovementPhase.FALL_START)
    private fun land(id:UUID)=source(id).copy(timestampEpochMillis=3,eventId=UUID.randomUUID(),position=BlockPosition(16,64,0),before=air,after=sand,action=ActionType.BLOCK_PLACE,movementPhase=BlockMovementPhase.FALL_LAND)
    private fun stores(test:(QueryableStorageBackend)->Unit) {
        listOf<QueryableStorageBackend>(InMemoryStorageBackend(),SqliteStorageBackend(Files.createTempDirectory("movement-sqlite").resolve("audit.db")),DuckDbStorageBackend(Files.createTempDirectory("movement-duck").resolve("audit.db"))).forEach{it.use{db->db.open();test(db)}}
    }
    @Test fun correlatedEndpointsAndFiltersPrecedePagination()=stores { db->
        val id=UUID.randomUUID();val a=source(id);val b=land(id);val other=land(UUID.randomUUID()).copy(timestampEpochMillis=4)
        db.append(listOf(a,b,other));assertEquals(listOf(b,a),db.lookupBlocks(BlockLookupQuery(movementId=id)).map{it.snapshot})
        assertEquals(listOf(a),db.lookupBlocks(BlockLookupQuery(movementId=id,limit=1,offset=1)).map{it.snapshot})
        assertTrue(db.lookupBlocks(BlockLookupQuery(movementId=id,causes=setOf(ChangeCause.PISTON))).isEmpty())
        assertTrue(db.lookupBlocks(BlockLookupQuery(movementId=id,actorName="Alex")).isEmpty())
        assertEquals(listOf(b,a),db.lookupBlocks(BlockLookupQuery(movementId=id,search=HistorySearchFilter(included=setOf(sand.blockId)))).map{it.snapshot})
    }
    @Test fun phaseAndCorrelationValidationRejectsIncompleteOrTemporaryStates() {
        val row=source(UUID.randomUUID())
        assertFailsWith<IllegalArgumentException>{row.copy(movementId=null)};assertFailsWith<IllegalArgumentException>{row.copy(movementPhase=null)}
        assertFailsWith<IllegalArgumentException>{row.copy(cause=ChangeCause.PLAYER)}
        assertFailsWith<IllegalArgumentException>{row.copy(movementPhase=BlockMovementPhase.PISTON)}
        assertFailsWith<IllegalArgumentException>{row.copy(after=BlockStateSnapshot(ResourceId.parse("minecraft:moving_piston")))}
        assertEquals(ChangeCause.PISTON,row.copy(cause=ChangeCause.PISTON,movementPhase=BlockMovementPhase.PISTON).cause)
    }
    @Test fun schema21UpgradePreservesOldPayloadAndCorrelationsSurviveRestart() {
        for(duck in listOf(false,true)) {
            val path=Files.createTempDirectory("movement-upgrade").resolve("audit.db");fun db():QueryableStorageBackend=if(duck)DuckDbStorageBackend(path)else SqliteStorageBackend(path)
            val old=source(UUID.randomUUID()).copy(cause=ChangeCause.PLAYER,movementId=null,movementPhase=null);val id=UUID.randomUUID();val a=source(id);val b=land(id)
            db().use{it.open();it.append(listOf(old))}
            DriverManager.getConnection((if(duck)"jdbc:duckdb:"else "jdbc:sqlite:")+path).use{c->c.createStatement().use{s->LegacyMovementSchema.strip(s);s.executeUpdate("DELETE FROM ex_schema_migrations WHERE version>=22");s.executeUpdate("UPDATE ex_meta SET meta_value='21' WHERE meta_key='schema_version'")}}
            db().use{it.open();assertEquals(23,it.health().schemaVersion);assertEquals(listOf(old),it.lookupBlocks(BlockLookupQuery()).map{r->r.snapshot});it.append(listOf(a,b))}
            db().use{it.open();assertEquals(listOf(b,a),it.lookupBlocks(BlockLookupQuery(movementId=id)).map{r->r.snapshot})}
        }
    }
    @Test fun purgeMayRemoveOldEndpointWithoutClaimingAnAtomicMovementGroup()=stores { db->
        val id=UUID.randomUUID();val a=source(id);val b=land(id);db.append(listOf(a,b))
        assertEquals(1,db.applyPurge(db.previewPurge(2)){true}.deleted.blocks)
        assertEquals(listOf(b),db.lookupBlocks(BlockLookupQuery(movementId=id)).map{it.snapshot})
    }
    @Test fun completeMovementIdsParseAndNonBlockCommandsRefuseThem() {
        val id=UUID.randomUUID();assertEquals(id,BlockCommandFilterParser.parse("m:$id c:gravity").getOrThrow().movementId)
        assertTrue(BlockCommandFilterParser.parse("m:0-0-0-0-0").isFailure)
        assertTrue(BlockCommandFilterParser.parse("m:$id movement:$id").isFailure)
        assertTrue(EntityCommandFilters.parse("m:$id").isFailure);assertTrue(FluidCommandFilters.parse("m:$id").isFailure)
    }
}
