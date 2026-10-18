package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.query.*
import kotlin.test.*

class BlockRollbackPreviewPlanTest {
    private val pos=BlockPosition(0,64,0)
    private fun state(id:String)=BlockStateSnapshot(ResourceId.parse(id))
    private fun row(id:Long,before:String,after:String,position:BlockPosition=pos,status:BlockRollbackState=BlockRollbackState.ACTIVE)=StoredBlockChange(id,BlockChangeSnapshot(id,ActorIdentity.Unknown,ResourceId.parse("overworld"),position,state(before),state(after),ChangeCause.PLAYER,ActionType.BLOCK_CHANGE),status)
    @Test fun simulatesSeveralRowsAtOnePositionWithoutChangingInput() {
        val plan=BlockRollbackPreviewPlan();val newest=row(2,"dirt","stone");val older=row(1,"air","dirt")
        val original=state("stone")
        assertEquals(BlockRollbackPreviewPlan.Outcome.WOULD_RESTORE,plan.observe(newest,original))
        assertEquals(BlockRollbackPreviewPlan.Outcome.WOULD_RESTORE,plan.observe(older,original))
        assertEquals(state("stone"),original);assertEquals(BlockRollbackState.ACTIVE,newest.rollbackState)
        assertEquals(2,plan.counts[BlockRollbackPreviewPlan.Outcome.WOULD_RESTORE])
    }
    @Test fun conflictBlocksOlderRowsOnlyAtTheSamePosition() {
        val plan=BlockRollbackPreviewPlan()
        assertEquals(BlockRollbackPreviewPlan.Outcome.STATE_MISMATCH,plan.observe(row(3,"dirt","stone"),state("gold_block")))
        assertEquals(BlockRollbackPreviewPlan.Outcome.BLOCKED_CHAIN,plan.observe(row(2,"air","dirt"),state("dirt")))
        assertEquals(BlockRollbackPreviewPlan.Outcome.WOULD_RESTORE,plan.observe(row(1,"air","stone",BlockPosition(1,64,0)),state("stone")))
    }
    @Test fun alreadyBeforeAndPendingRowsRemainReadOnly() {
        for(status in listOf(BlockRollbackState.ACTIVE,BlockRollbackState.PENDING)) {
            val row=row(1,"air","stone",status=status);val plan=BlockRollbackPreviewPlan()
            assertEquals(BlockRollbackPreviewPlan.Outcome.ALREADY_BEFORE,plan.observe(row,state("air")))
            assertEquals(status,row.rollbackState)
        }
    }
    @Test fun unloadedUnreadableAndUnsupportedTargetsStopTheChain() {
        for(reason in listOf(BlockRollbackPreviewPlan.Outcome.UNLOADED,BlockRollbackPreviewPlan.Outcome.READ_FAILURE)) {
            val plan=BlockRollbackPreviewPlan()
            assertEquals(reason,plan.observe(row(2,"dirt","stone"),null,reason))
            assertEquals(BlockRollbackPreviewPlan.Outcome.BLOCKED_CHAIN,plan.observe(row(1,"air","dirt"),state("dirt")))
        }
        val plan=BlockRollbackPreviewPlan()
        assertEquals(BlockRollbackPreviewPlan.Outcome.UNSUPPORTED,plan.observe(row(2,"example:missing","stone"),state("stone"),targetSupported=false))
        assertEquals(BlockRollbackPreviewPlan.Outcome.BLOCKED_CHAIN,plan.observe(row(1,"air","example:missing"),state("stone")))
    }
}
