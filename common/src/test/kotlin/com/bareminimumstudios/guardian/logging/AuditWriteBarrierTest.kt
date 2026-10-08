package com.bareminimumstudios.guardian.logging

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.StorageBackend
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class AuditWriteBarrierTest {
    private fun entry() = BlockChangeSnapshot(1,ActorIdentity.Unknown,ResourceId.parse("minecraft:overworld"),BlockPosition(1,64,1),BlockStateSnapshot(ResourceId.parse("minecraft:air")),BlockStateSnapshot(ResourceId.parse("minecraft:stone")),ChangeCause.PLAYER,ActionType.BLOCK_PLACE)
    private class Backend(blockAppend: Boolean=false,blockFlush: Boolean=false,val appendFailures: Int=0,val flushFailures: Int=0): StorageBackend {
        override val id="barrier-test"
        val appendEntered=CountDownLatch(1);val flushEntered=CountDownLatch(1)
        val appendGate=CountDownLatch(if(blockAppend) 1 else 0);val flushGate=CountDownLatch(if(blockFlush) 1 else 0)
        val appends=AtomicInteger();val flushes=AtomicInteger();val stored=CopyOnWriteArrayList<LogEntry>()
        override fun open()=Unit
        override fun append(entries: List<LogEntry>) {
            appendEntered.countDown();waitFor(appendGate)
            if(appends.getAndIncrement()<appendFailures) error("synthetic append failure")
            stored.addAll(entries)
        }
        override fun flush() {
            flushEntered.countDown();waitFor(flushGate)
            if(flushes.getAndIncrement()<flushFailures) error("synthetic flush failure")
        }
        override fun close()=Unit
        private fun waitFor(gate: CountDownLatch) {
            while(gate.count>0) try { check(gate.await(5,TimeUnit.SECONDS)) } catch(_: InterruptedException) { }
        }
    }
    private fun run(backend: Backend,block: (BufferedLogPipeline)->Unit) {
        val pipeline=BufferedLogPipeline(backend,64,1,10,5);pipeline.start()
        try { block(pipeline) } finally { backend.appendGate.countDown();backend.flushGate.countDown();assertTrue(pipeline.stopGracefully(Duration.ofSeconds(3))) }
    }
    @Test fun acceptedPrefixWaitsForCommitAndDoesNotWaitForLaterSubmissions() {
        val backend=Backend(blockAppend=true)
        run(backend) { pipeline ->
            assertEquals(SubmissionResult.ACCEPTED,pipeline.submit(entry()));assertTrue(backend.appendEntered.await(2,TimeUnit.SECONDS))
            val barrier=pipeline.writeBarrier();assertEquals(1,barrier.acceptedThrough);assertFalse(barrier.result.toCompletableFuture().isDone)
            assertEquals(SubmissionResult.ACCEPTED,pipeline.submit(entry()));backend.appendGate.countDown()
            val fence=barrier.result.toCompletableFuture().get(2,TimeUnit.SECONDS)
            assertEquals(1,fence.acceptedThrough);assertEquals(1,fence.persistedThrough)
        }
        assertEquals(2,backend.stored.size)
    }
    @Test fun committedEntriesStillWaitForFlushAcknowledgement() {
        val backend=Backend(blockFlush=true)
        run(backend) { pipeline ->
            pipeline.submit(entry());val barrier=pipeline.writeBarrier();assertTrue(backend.flushEntered.await(2,TimeUnit.SECONDS))
            assertEquals(1,pipeline.metrics().persisted);assertFalse(barrier.result.toCompletableFuture().isDone)
            backend.flushGate.countDown();assertEquals(1,barrier.result.toCompletableFuture().get(2,TimeUnit.SECONDS).acceptedThrough)
        }
    }
    @Test fun anEmptyPrefixIsAcknowledgedOnlyAfterStorageFlush() {
        val backend=Backend()
        run(backend) { pipeline -> val fence=pipeline.writeBarrier().result.toCompletableFuture().get(2,TimeUnit.SECONDS);assertEquals(0,fence.acceptedThrough);assertTrue(backend.flushes.get()>0) }
    }
    @Test fun writeRetriesDoNotAcknowledgeUncommittedEntries() {
        val backend=Backend(appendFailures=1)
        run(backend) { pipeline -> pipeline.submit(entry());val fence=pipeline.writeBarrier().result.toCompletableFuture().get(2,TimeUnit.SECONDS);assertEquals(1,fence.persistedThrough);assertEquals(2,backend.appends.get());assertEquals(1,pipeline.metrics().writeFailures) }
        assertEquals(1,backend.stored.size)
    }
    @Test fun flushFailureRejectsFenceAndASeparateRequestCanRetry() {
        val backend=Backend(flushFailures=1)
        run(backend) { pipeline ->
            pipeline.submit(entry());assertFailsWith<ExecutionException> { pipeline.writeBarrier().result.toCompletableFuture().get(2,TimeUnit.SECONDS) }
            assertEquals(1,pipeline.metrics().writeFailures)
            assertEquals(1,pipeline.writeBarrier().result.toCompletableFuture().get(2,TimeUnit.SECONDS).acceptedThrough)
        }
    }
    @Test fun pendingRequestsAreBoundedAndCancellationReleasesCapacity() {
        val backend=Backend(blockAppend=true)
        run(backend) { pipeline ->
            pipeline.submit(entry());assertTrue(backend.appendEntered.await(2,TimeUnit.SECONDS))
            val barriers=List(32) { pipeline.writeBarrier() }
            assertFailsWith<ExecutionException> { pipeline.writeBarrier().result.toCompletableFuture().get(2,TimeUnit.SECONDS) }
            barriers.first().cancel();assertTrue(barriers.first().result.toCompletableFuture().isCompletedExceptionally)
            val replacement=pipeline.writeBarrier();backend.appendGate.countDown();assertEquals(1,replacement.result.toCompletableFuture().get(2,TimeUnit.SECONDS).acceptedThrough)
        }
    }
    @Test fun callerCannotForgeTheInternalCompletionReceipt() {
        val backend=Backend(blockAppend=true)
        run(backend) { pipeline ->
            pipeline.submit(entry());assertTrue(backend.appendEntered.await(2,TimeUnit.SECONDS));val barrier=pipeline.writeBarrier()
            val copy=barrier.result.toCompletableFuture();copy.complete(AuditWriteFence(999,999));assertFalse(barrier.result.toCompletableFuture().isDone)
            backend.appendGate.countDown();assertEquals(1,barrier.result.toCompletableFuture().get(2,TimeUnit.SECONDS).acceptedThrough)
        }
    }
    @Test fun lifecycleRejectsNewRequestsAndFailsOutstandingWaiters() {
        val backend=Backend(blockAppend=true);val pipeline=BufferedLogPipeline(backend,64,1,10,5)
        assertFailsWith<ExecutionException> { pipeline.writeBarrier().result.toCompletableFuture().get(2,TimeUnit.SECONDS) }
        pipeline.start();pipeline.submit(entry());assertTrue(backend.appendEntered.await(2,TimeUnit.SECONDS));val barrier=pipeline.writeBarrier()
        try {
            assertFalse(pipeline.stopGracefully(Duration.ofMillis(20)))
            assertTrue(barrier.result.toCompletableFuture().isCompletedExceptionally)
            assertFailsWith<ExecutionException> { pipeline.writeBarrier().result.toCompletableFuture().get(2,TimeUnit.SECONDS) }
        } finally { backend.appendGate.countDown();assertTrue(pipeline.stopGracefully(Duration.ofSeconds(3))) }
    }
    @Test fun callbacksCanSubmitWithoutHoldingThePipelineGate() {
        val backend=Backend()
        run(backend) { pipeline ->
            val callback=pipeline.writeBarrier().result.thenApply { pipeline.submit(entry()) }.toCompletableFuture()
            assertEquals(SubmissionResult.ACCEPTED,callback.get(2,TimeUnit.SECONDS))
            assertEquals(1,pipeline.writeBarrier().result.toCompletableFuture().get(2,TimeUnit.SECONDS).acceptedThrough)
        }
    }
    @Test fun concurrentSubmissionsHaveOrderedAcceptedPrefixes() {
        val backend=Backend()
        run(backend) { pipeline ->
            val executor=Executors.newFixedThreadPool(4)
            try {
                val tasks=List(16) { executor.submit(Callable {
                    assertEquals(SubmissionResult.ACCEPTED,pipeline.submit(entry()))
                    val barrier=pipeline.writeBarrier()
                    val fence=barrier.result.toCompletableFuture().get(2,TimeUnit.SECONDS)
                    assertEquals(barrier.acceptedThrough,fence.acceptedThrough)
                    assertTrue(fence.persistedThrough>=fence.acceptedThrough)
                    assertTrue(backend.stored.size.toLong()>=fence.acceptedThrough)
                }) }
                tasks.forEach { it.get(3,TimeUnit.SECONDS) }
                assertEquals(16,pipeline.metrics().accepted)
            } finally { executor.shutdownNow() }
        }
        assertEquals(16,backend.stored.size)
    }
    @Test fun startupFailureFailsRequestsMadeWhileStorageWasOpening() {
        val opening=CountDownLatch(1);val release=CountDownLatch(1)
        val backend=object: StorageBackend {
            override val id="open-failure"
            override fun open() { opening.countDown();check(release.await(2,TimeUnit.SECONDS));error("synthetic open failure") }
            override fun append(entries: List<LogEntry>)=Unit
            override fun flush()=Unit
            override fun close()=Unit
        }
        val pipeline=BufferedLogPipeline(backend,4,1,10)
        val executor=Executors.newSingleThreadExecutor()
        try {
            val startup=executor.submit(Callable { runCatching { pipeline.start() } })
            assertTrue(opening.await(2,TimeUnit.SECONDS));val barrier=pipeline.writeBarrier();release.countDown()
            assertTrue(startup.get(2,TimeUnit.SECONDS).isFailure)
            assertFailsWith<ExecutionException> { barrier.result.toCompletableFuture().get(2,TimeUnit.SECONDS) }
            assertEquals(PipelineState.FAILED,pipeline.currentState())
        } finally { release.countDown();executor.shutdownNow() }
    }

}
