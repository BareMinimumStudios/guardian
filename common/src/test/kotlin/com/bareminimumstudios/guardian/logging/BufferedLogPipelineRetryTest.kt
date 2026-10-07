package com.bareminimumstudios.guardian.logging

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.StorageBackend
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BufferedLogPipelineRetryTest {
    @Test
    fun retriesSameBatchAfterStorageFailure() {
        val backend = FailOnceBackend()
        val pipeline = BufferedLogPipeline(
            storage = backend,
            queueCapacity = 16,
            batchSize = 4,
            flushIntervalMillis = 10,
            retryDelayMillis = 5
        )

        pipeline.start()
        assertEquals(SubmissionResult.ACCEPTED, pipeline.submit(sampleChange()))
        assertTrue(pipeline.stopGracefully(Duration.ofSeconds(2)))
        assertEquals(1, backend.persisted.size)
        assertEquals(1, pipeline.metrics().writeFailures)
        assertEquals(1, pipeline.metrics().persisted)
    }

    private class FailOnceBackend : StorageBackend {
        override val id: String = "fail-once"
        private val calls = AtomicInteger()
        val persisted = mutableListOf<LogEntry>()

        override fun open() = Unit

        override fun append(entries: List<LogEntry>) {
            if (calls.getAndIncrement() == 0) error("synthetic first write failure")
            persisted += entries
        }

        override fun flush() = Unit
        override fun close() = Unit
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
