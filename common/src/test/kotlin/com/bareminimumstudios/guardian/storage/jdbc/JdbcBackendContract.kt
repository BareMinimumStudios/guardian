package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.ActionType
import com.bareminimumstudios.guardian.domain.ActorIdentity
import com.bareminimumstudios.guardian.domain.BinaryPayload
import com.bareminimumstudios.guardian.domain.BlockBounds
import com.bareminimumstudios.guardian.domain.BlockChangeSnapshot
import com.bareminimumstudios.guardian.domain.BlockPosition
import com.bareminimumstudios.guardian.domain.BlockStateSnapshot
import com.bareminimumstudios.guardian.domain.ChangeCause
import com.bareminimumstudios.guardian.domain.ResourceId
import com.bareminimumstudios.guardian.storage.QueryableStorageBackend
import com.bareminimumstudios.guardian.storage.query.BlockLookupQuery
import com.bareminimumstudios.guardian.storage.query.BlockRollbackState
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

abstract class JdbcBackendContract {
    abstract fun backend(path: Path): QueryableStorageBackend

    protected fun verifyRoundTrip(path: Path) {
        val eventId = UUID.randomUUID()
        val actorId = UUID.randomUUID()
        val snapshot = BlockChangeSnapshot(
            timestampEpochMillis = 1_700_000_000_123L,
            actor = ActorIdentity.Player(actorId, "Sayf"),
            dimension = ResourceId.parse("minecraft:overworld"),
            position = BlockPosition(10, 64, -20),
            before = BlockStateSnapshot(ResourceId.parse("minecraft:air")),
            after = BlockStateSnapshot(
                ResourceId.parse("minecraft:oak_sign"),
                mapOf("rotation" to "3", "waterlogged" to "false"),
                BinaryPayload.of(byteArrayOf(1, 2, 3, 4))
            ),
            cause = ChangeCause.PLAYER,
            action = ActionType.BLOCK_PLACE,
            eventId = eventId
        )

        backend(path).use { first ->
            first.open()
            first.append(listOf(snapshot, snapshot)) // event UUID makes retries idempotent
            val results = first.lookupBlocks(BlockLookupQuery(position = snapshot.position))
            assertEquals(1, results.size)
            assertEquals(snapshot.eventId, results.single().snapshot.eventId)
            assertEquals(snapshot.actor, results.single().snapshot.actor)
            assertEquals(snapshot.after.blockId, results.single().snapshot.after.blockId)
            assertEquals(snapshot.after.properties, results.single().snapshot.after.properties)
            assertContentEquals(byteArrayOf(1, 2, 3, 4), results.single().snapshot.after.blockEntityData!!.copyBytes())
            assertEquals(BlockRollbackState.ACTIVE, results.single().rollbackState)
            first.setBlockRollbackState(listOf(results.single().rowId), BlockRollbackState.PENDING)
            assertEquals(BlockRollbackState.PENDING, first.lookupBlocks(BlockLookupQuery(position = snapshot.position)).single().rollbackState)
            first.setBlockRollbackState(listOf(results.single().rowId), BlockRollbackState.ROLLED_BACK)
            assertTrue(first.lookupBlocks(BlockLookupQuery(position = snapshot.position, includeRolledBack = false)).isEmpty())
        }

        backend(path).use { reopened ->
            reopened.open()
            assertEquals(1, reopened.lookupBlocks(BlockLookupQuery(actorUuid = actorId)).size)
            assertEquals(GuardianSchema.CURRENT_VERSION, reopened.health().schemaVersion)
        }
    }

    protected fun verifyExclusiveLock(path: Path) {
        val first = backend(path)
        val second = backend(path)
        try {
            first.open()
            val error = assertFailsWith<IllegalStateException> { second.open() }
            assertTrue(error.message.orEmpty().contains("already in use"))
        } finally {
            runCatching { second.close() }
            first.close()
        }
    }
    protected fun verifyBoundsQuery(path: Path) {
        val dimension = ResourceId.parse("minecraft:overworld")
        val actor = ActorIdentity.System("bounds-test")
        fun snapshot(x: Int, y: Int, z: Int) = BlockChangeSnapshot(
            timestampEpochMillis = 1_700_000_100_000L + x,
            actor = actor,
            dimension = dimension,
            position = BlockPosition(x, y, z),
            before = BlockStateSnapshot(ResourceId.parse("minecraft:air")),
            after = BlockStateSnapshot(ResourceId.parse("minecraft:stone")),
            cause = ChangeCause.WORLD_EDIT,
            action = ActionType.BLOCK_PLACE,
            eventId = UUID.randomUUID()
        )

        backend(path).use { storage ->
            storage.open()
            storage.append(
                listOf(
                    snapshot(-2, 60, -2),
                    snapshot(0, 64, 0),
                    snapshot(2, 68, 2),
                    snapshot(3, 64, 0),
                    snapshot(0, 69, 0)
                )
            )
            val rows = storage.lookupBlocks(
                BlockLookupQuery(
                    dimension = dimension,
                    bounds = BlockBounds(
                        min = BlockPosition(-2, 60, -2),
                        max = BlockPosition(2, 68, 2)
                    ),
                    limit = 20
                )
            )
            assertEquals(3, rows.size)
            assertTrue(rows.all { it.snapshot.position.x in -2..2 })
            assertTrue(rows.all { it.snapshot.position.y in 60..68 })
            assertTrue(rows.all { it.snapshot.position.z in -2..2 })
        }
    }

}
