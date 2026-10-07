package com.bareminimumstudios.guardian.storage

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.query.BlockLookupQuery
import com.bareminimumstudios.guardian.storage.query.BlockRollbackState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryStorageBackendTest {
    @Test
    fun appendsEntriesAfterOpen() {
        val backend = InMemoryStorageBackend()
        backend.open()
        backend.append(listOf(sampleChange(BlockPosition(1, 64, 1))))
        assertEquals(1, backend.snapshot().size)
        backend.close()
    }

    @Test
    fun radiusLookupAndRollbackJournalWork() {
        val backend = InMemoryStorageBackend()
        backend.open()
        backend.append(
            listOf(
                sampleChange(BlockPosition(0, 64, 0)),
                sampleChange(BlockPosition(3, 64, 3)),
                sampleChange(BlockPosition(20, 64, 20))
            )
        )

        val rows = backend.lookupBlocks(
            BlockLookupQuery(position = BlockPosition(0, 64, 0), radius = 5, limit = 10)
        )
        assertEquals(2, rows.size)
        val first = rows.first()
        assertEquals(BlockRollbackState.ACTIVE, first.rollbackState)

        backend.setBlockRollbackState(listOf(first.rowId), BlockRollbackState.PENDING)
        assertEquals(BlockRollbackState.PENDING, backend.lookupBlocks(BlockLookupQuery(limit = 10)).first { it.rowId == first.rowId }.rollbackState)

        backend.setBlockRollbackState(listOf(first.rowId), BlockRollbackState.ROLLED_BACK)
        val activeOnly = backend.lookupBlocks(BlockLookupQuery(includeRolledBack = false, limit = 10))
        assertTrue(activeOnly.none { it.rowId == first.rowId })
        backend.close()
    }

    @Test
    fun cuboidBoundsLookupIsInclusive() {
        val backend = InMemoryStorageBackend()
        backend.open()
        backend.append(
            listOf(
                sampleChange(BlockPosition(-2, 63, -2)),
                sampleChange(BlockPosition(2, 70, 2)),
                sampleChange(BlockPosition(3, 70, 2))
            )
        )
        val rows = backend.lookupBlocks(
            BlockLookupQuery(
                bounds = BlockBounds(BlockPosition(-2, 63, -2), BlockPosition(2, 70, 2)),
                limit = 10
            )
        )
        assertEquals(2, rows.size)
        backend.close()
    }

    private fun sampleChange(position: BlockPosition) = BlockChangeSnapshot(
        timestampEpochMillis = System.currentTimeMillis(),
        actor = ActorIdentity.Player(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Tester"),
        dimension = ResourceId.parse("minecraft:overworld"),
        position = position,
        before = BlockStateSnapshot(ResourceId.parse("minecraft:air")),
        after = BlockStateSnapshot(ResourceId.parse("minecraft:stone")),
        cause = ChangeCause.PLAYER,
        action = ActionType.BLOCK_PLACE
    )
}
