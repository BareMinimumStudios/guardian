package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.ActionType
import com.bareminimumstudios.guardian.domain.ActorIdentity
import com.bareminimumstudios.guardian.domain.BlockChangeSnapshot
import com.bareminimumstudios.guardian.domain.BlockPosition
import com.bareminimumstudios.guardian.domain.BlockStateSnapshot
import com.bareminimumstudios.guardian.domain.ChangeCause
import com.bareminimumstudios.guardian.domain.ResourceId
import com.bareminimumstudios.guardian.storage.query.BlockRollbackState
import com.bareminimumstudios.guardian.storage.query.StoredBlockChange
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class BlockRollbackDecisionTest {
    private val before = BlockStateSnapshot(ResourceId.parse("minecraft:stone"), emptyMap(), null)
    private val after = BlockStateSnapshot(ResourceId.parse("minecraft:oak_planks"), emptyMap(), null)
    private val unrelated = BlockStateSnapshot(ResourceId.parse("minecraft:diamond_block"), emptyMap(), null)

    private val row = StoredBlockChange(
        rowId = 7,
        snapshot = BlockChangeSnapshot(
            eventId = UUID.randomUUID(),
            timestampEpochMillis = 1234L,
            actor = ActorIdentity.Player(UUID.randomUUID(), "Tester"),
            dimension = ResourceId.parse("minecraft:overworld"),
            position = BlockPosition(1, 64, 2),
            before = before,
            after = after,
            cause = ChangeCause.PLAYER,
            action = ActionType.BLOCK_CHANGE
        ),
        rollbackState = BlockRollbackState.ACTIVE
    )

    @Test
    fun `recorded after state is safe to apply`() {
        assertEquals(BlockRollbackDecision.APPLY, BlockRollbackDecision.decide(after, row))
    }

    @Test
    fun `recorded before state is already applied`() {
        assertEquals(BlockRollbackDecision.ALREADY_APPLIED, BlockRollbackDecision.decide(before, row))
    }

    @Test
    fun `unrelated live state is never overwritten`() {
        assertEquals(BlockRollbackDecision.SKIP_STATE_MISMATCH, BlockRollbackDecision.decide(unrelated, row))
    }
}
