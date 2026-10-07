package com.bareminimumstudios.guardian.logging

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.InMemoryStorageBackend
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BufferedLogPipelineTest {
    @Test
    fun persistsAcceptedEntryBeforeCleanStop() {
        val backend = InMemoryStorageBackend()
        val pipeline = BufferedLogPipeline(
            storage = backend,
            queueCapacity = 16,
            batchSize = 4,
            flushIntervalMillis = 10
        )

        pipeline.start()
        assertEquals(SubmissionResult.ACCEPTED, pipeline.submit(sampleChange()))
        assertTrue(pipeline.stopGracefully(Duration.ofSeconds(2)))
        assertEquals(1, backend.snapshot().size)
        assertEquals(1, pipeline.metrics().persisted)
    }

    private fun sampleChange() = BlockChangeSnapshot(
        timestampEpochMillis = 1L,
        actor = ActorIdentity.Player(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Tester"),
        dimension = ResourceId.parse("minecraft:overworld"),
        position = BlockPosition(1, 64, 1),
        before = BlockStateSnapshot(ResourceId.parse("minecraft:air")),
        after = BlockStateSnapshot(ResourceId.parse("minecraft:stone")),
        cause = ChangeCause.PLAYER,
        action = ActionType.BLOCK_PLACE
    )
}
