package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.*
import com.bareminimumstudios.guardian.storage.jdbc.*
import com.bareminimumstudios.guardian.storage.query.*
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*
import org.junit.jupiter.api.Test

class BlockRestoreTest {
    private val pos = BlockPosition(1,64,1)
    private fun state(id: String) = BlockStateSnapshot(ResourceId.parse(id))
    private fun snapshot(time: Long, before: String = "air", after: String = "stone") = BlockChangeSnapshot(time,ActorIdentity.Unknown,ResourceId.parse("overworld"),pos,state(before),state(after),ChangeCause.PLAYER,ActionType.BLOCK_CHANGE)
    private fun row(time: Long, before: String, after: String, status: BlockRollbackState = BlockRollbackState.ROLLED_BACK) = StoredBlockChange(time,snapshot(time,before,after),status)
    private fun stores(test: (QueryableStorageBackend) -> Unit) {
        listOf<QueryableStorageBackend>(InMemoryStorageBackend(),SqliteStorageBackend(Files.createTempDirectory("restore-sqlite").resolve("audit.db")),DuckDbStorageBackend(Files.createTempDirectory("restore-duck").resolve("audit.db"))).forEach { it.use { db -> db.open(); test(db) } }
    }
    @Test fun restoreViewReversesDirectionWithoutChangingAuditData() {
        val original=row(1,"air","stone")
        val view=BlockHistoryOperation.RESTORE.view(original)
        assertEquals(original.snapshot.after,view.snapshot.before)
        assertEquals(original.snapshot.before,view.snapshot.after)
        assertEquals(state("air"),original.snapshot.before)
        assertEquals(original.snapshot.eventId,view.snapshot.eventId)
        assertSame(original,BlockHistoryOperation.ROLLBACK.view(original))
    }
    @Test fun restoreReplaysOldestFirstAsASimulatedChain() {
        val plan=BlockRollbackPreviewPlan(BlockHistoryOperation.RESTORE)
        assertEquals(BlockRollbackPreviewPlan.Outcome.WOULD_RESTORE,plan.observe(row(1,"air","stone"),state("air")))
        assertEquals(BlockRollbackPreviewPlan.Outcome.WOULD_RESTORE,plan.observe(row(2,"stone","dirt"),state("air")))
        assertEquals(2,plan.counts[BlockRollbackPreviewPlan.Outcome.WOULD_RESTORE])
    }
    @Test fun restoreConflictBlocksLaterChangesAtSamePosition() {
        val plan=BlockRollbackPreviewPlan(BlockHistoryOperation.RESTORE)
        assertEquals(BlockRollbackPreviewPlan.Outcome.STATE_MISMATCH,plan.observe(row(1,"air","stone"),state("gold_block")))
        assertEquals(BlockRollbackPreviewPlan.Outcome.BLOCKED_CHAIN,plan.observe(row(2,"stone","dirt"),state("stone")))
    }
    @Test fun restorePendingAtTargetCanReconcileWithoutAnotherWrite() {
        val pending=row(1,"air","stone",BlockRollbackState.RESTORE_PENDING)
        assertEquals(BlockRollbackDecision.ALREADY_APPLIED,BlockRollbackDecision.decide(state("stone"),BlockHistoryOperation.RESTORE.view(pending)))
        assertEquals(BlockRollbackDecision.APPLY,BlockRollbackDecision.decide(state("air"),BlockHistoryOperation.RESTORE.view(pending)))
        assertEquals(BlockRollbackState.RESTORE_PENDING,pending.rollbackState)
    }
    @Test fun unavailableAndUnsupportedRestoreTargetsBlockTheirChain() {
        for(outcome in listOf(BlockRollbackPreviewPlan.Outcome.UNLOADED,BlockRollbackPreviewPlan.Outcome.READ_FAILURE)) {
            val plan=BlockRollbackPreviewPlan(BlockHistoryOperation.RESTORE)
            assertEquals(outcome,plan.observe(row(1,"air","stone"),null,outcome))
            assertEquals(BlockRollbackPreviewPlan.Outcome.BLOCKED_CHAIN,plan.observe(row(2,"stone","dirt"),state("stone")))
        }
        assertEquals(BlockRollbackPreviewPlan.Outcome.UNSUPPORTED,BlockRollbackPreviewPlan(BlockHistoryOperation.RESTORE).observe(row(1,"air","stone"),state("air"),targetSupported=false))
    }
    @Test fun explicitJournalSelectionRunsBeforePaging() = stores { db ->
        db.append((1L..4L).map { snapshot(it) })
        val rows=db.lookupBlocks(BlockLookupQuery(oldestFirst=true))
        db.setBlockRollbackState(listOf(rows[1].rowId),BlockRollbackState.ROLLED_BACK)
        db.setBlockRollbackState(listOf(rows[2].rowId),BlockRollbackState.PENDING)
        db.setBlockRollbackState(listOf(rows[3].rowId),BlockRollbackState.RESTORE_PENDING)
        val q=BlockLookupQuery(includeRolledBack=false,oldestFirst=true,rollbackStates=BlockHistoryOperation.RESTORE.eligibleStates,limit=1)
        assertEquals(listOf(2L),db.lookupBlocks(q).map { it.snapshot.timestampEpochMillis })
        assertEquals(listOf(4L),db.lookupBlocks(q.copy(offset=1)).map { it.snapshot.timestampEpochMillis })
        assertEquals(listOf(3L,1L),db.lookupBlocks(BlockLookupQuery(includeRolledBack=false)).map { it.snapshot.timestampEpochMillis })
        assertEquals(4,db.lookupBlocks(BlockLookupQuery()).size)
    }
    @Test fun restoredHistoryCanBeRolledBackAgainWithoutDuplicateRows() = stores { db ->
        val audit=snapshot(1);db.append(listOf(audit));val id=db.lookupBlocks(BlockLookupQuery()).single().rowId
        for(status in listOf(BlockRollbackState.PENDING,BlockRollbackState.ROLLED_BACK,BlockRollbackState.RESTORE_PENDING,BlockRollbackState.ACTIVE))db.setBlockRollbackState(listOf(id),status)
        assertEquals(listOf(audit),db.lookupBlocks(BlockLookupQuery()).map { it.snapshot })
        assertTrue(db.lookupBlocks(BlockLookupQuery(rollbackStates=BlockHistoryOperation.RESTORE.eligibleStates)).isEmpty())
        assertEquals(1,db.lookupBlocks(BlockLookupQuery(rollbackStates=BlockHistoryOperation.ROLLBACK.eligibleStates)).size)
    }
    @Test fun schema12UpgradePreservesRolledBackHistoryAcrossRestart() {
        for(duck in listOf(false,true)) {
            val path=Files.createTempDirectory("restore-upgrade").resolve("audit.db")
            fun db():QueryableStorageBackend=if(duck)DuckDbStorageBackend(path)else SqliteStorageBackend(path)
            val audit=snapshot(1);var id=0L
            db().use { it.open();it.append(listOf(audit));id=it.lookupBlocks(BlockLookupQuery()).single().rowId;it.setBlockRollbackState(listOf(id),BlockRollbackState.ROLLED_BACK) }
            DriverManager.getConnection((if(duck)"jdbc:duckdb:" else "jdbc:sqlite:")+path).use { c ->c.createStatement().use { s ->s.executeUpdate("DROP TABLE ex_entity");s.executeUpdate("DROP TABLE IF EXISTS ex_fluid");s.executeUpdate("DELETE FROM ex_schema_migrations WHERE version >= 13");s.executeUpdate("UPDATE ex_meta SET meta_value='12' WHERE meta_key='schema_version'") } }
            db().use { it.open();assertEquals(20,it.health().schemaVersion);val stored=it.lookupBlocks(BlockLookupQuery(rollbackStates=BlockHistoryOperation.RESTORE.eligibleStates)).single();assertEquals(audit,stored.snapshot);assertEquals(id,stored.rowId);it.setBlockRollbackState(listOf(id),BlockRollbackState.RESTORE_PENDING) }
            db().use { it.open();assertEquals(BlockRollbackState.RESTORE_PENDING,it.lookupBlocks(BlockLookupQuery()).single().rollbackState) }
        }
    }
}
