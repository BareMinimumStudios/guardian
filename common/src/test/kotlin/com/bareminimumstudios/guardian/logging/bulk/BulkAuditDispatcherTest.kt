package com.bareminimumstudios.guardian.logging.bulk

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.logging.BufferedLogPipeline
import com.bareminimumstudios.guardian.storage.InMemoryStorageBackend
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BulkAuditDispatcherTest {
    @Test
    fun reservesCapacityAndDrainsLargeOperationWithoutDrops() {
        val backend = InMemoryStorageBackend()
        val pipeline = BufferedLogPipeline(backend, queueCapacity = 8, batchSize = 4, flushIntervalMillis = 5)
        pipeline.start()
        val bulk = BulkAuditDispatcher(pipeline, maxPendingOperations = 1, maxEntriesPerOperation = 1_000, chunkSize = 32)
        bulk.start()

        val capture = assertNotNull(bulk.tryBegin("worldedit:test"))
        assertNull(bulk.tryBegin("worldedit:second"))
        repeat(250) { capture.record(sampleChange(it)) }
        capture.commit()

        assertTrue(bulk.stopGracefully(Duration.ofSeconds(5)))
        assertTrue(pipeline.stopGracefully(Duration.ofSeconds(5)))
        assertEquals(250, backend.snapshot().size)
        assertEquals(250, bulk.metrics().submittedEntries)
        assertTrue(bulk.metrics().streamedChunks >= 8)
        assertTrue(bulk.metrics().backpressureRetries >= 0)
    }


    @Test
    fun streamsChunksBeforeOperationCommitButRetainsReservationUntilFinish() {
        val backend = InMemoryStorageBackend()
        val pipeline = BufferedLogPipeline(backend, queueCapacity = 8, batchSize = 4, flushIntervalMillis = 5)
        pipeline.start()
        val bulk = BulkAuditDispatcher(
            pipeline,
            maxPendingOperations = 1,
            maxEntriesPerOperation = 500,
            chunkSize = 16
        )
        bulk.start()
        val capture = assertNotNull(bulk.tryBegin("worldedit:stream"))
        repeat(64) { capture.record(sampleChange(it)) }

        val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
        while (bulk.metrics().submittedEntries < 16 && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        assertTrue(bulk.metrics().submittedEntries >= 16)
        assertEquals(1, bulk.metrics().reservedOperations)
        assertNull(bulk.tryBegin("worldedit:blocked-until-finish"))

        capture.commit()
        assertTrue(bulk.stopGracefully(Duration.ofSeconds(5)))
        assertTrue(pipeline.stopGracefully(Duration.ofSeconds(5)))
        assertEquals(64, backend.snapshot().size)
        assertEquals(0, bulk.metrics().reservedOperations)
    }

    @Test
    fun sessionRefusesEntriesPastConfiguredLimitBeforeMutationBoundary() {
        val backend = InMemoryStorageBackend()
        val pipeline = BufferedLogPipeline(backend, queueCapacity = 4, batchSize = 2, flushIntervalMillis = 5)
        pipeline.start()
        val bulk = BulkAuditDispatcher(pipeline, maxPendingOperations = 1, maxEntriesPerOperation = 2)
        bulk.start()
        val capture = assertNotNull(bulk.tryBegin("worldedit:test"))
        assertTrue(capture.hasCapacity())
        capture.record(sampleChange(1))
        capture.record(sampleChange(2))
        assertTrue(!capture.hasCapacity())
        capture.commit()
        assertTrue(bulk.stopGracefully(Duration.ofSeconds(2)))
        assertTrue(pipeline.stopGracefully(Duration.ofSeconds(2)))
    }

    private fun sampleChange(x: Int) = BlockChangeSnapshot(
        timestampEpochMillis = x.toLong(),
        actor = ActorIdentity.Player(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Tester"),
        dimension = ResourceId.parse("minecraft:overworld"),
        position = BlockPosition(x, 64, 0),
        before = BlockStateSnapshot(ResourceId.parse("minecraft:air")),
        after = BlockStateSnapshot(ResourceId.parse("minecraft:stone")),
        cause = ChangeCause.WORLD_EDIT,
        action = ActionType.BLOCK_PLACE
    )
}
