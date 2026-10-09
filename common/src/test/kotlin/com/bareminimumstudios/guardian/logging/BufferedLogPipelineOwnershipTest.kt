package com.bareminimumstudios.guardian.logging
import com.bareminimumstudios.guardian.storage.*
import com.bareminimumstudios.guardian.domain.LogEntry
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
class BufferedLogPipelineOwnershipTest {
    @Test fun externallyOwnedStorageRemainsOpenAfterWriterDrain()=exercise(false)
    @Test fun defaultOwnershipClosesStorageBeforeReportingActualDrain()=exercise(true)
    private fun exercise(owned:Boolean){
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val count=AtomicInteger()
        val backend=object:StorageBackend {
            override val id="fixture"
            override fun open()=Unit
            override fun append(entries:List<LogEntry>)=Unit
            override fun flush(){entered.countDown();while(release.count>0)try{release.await(1,TimeUnit.SECONDS)}catch(_:InterruptedException){}}
            override fun close(){count.incrementAndGet()}
        }
        val pipeline=BufferedLogPipeline(backend,4,2,10,closeStorageOnStop=owned);pipeline.start()
        try {
            assertFalse(pipeline.stopGracefully(Duration.ofMillis(10)));assertTrue(entered.await(2,TimeUnit.SECONDS))
            assertFalse(pipeline.drained.toCompletableFuture().isDone)
            pipeline.drained.toCompletableFuture().cancel(false);assertEquals(0,count.get())
        } finally {release.countDown();pipeline.drained.toCompletableFuture().get(2,TimeUnit.SECONDS)}
        assertEquals(if(owned)1 else 0,count.get());assertTrue(pipeline.stopGracefully(Duration.ofSeconds(1)))
        if(!owned){val close=StorageShutdown(listOf(pipeline.drained)){backend.close()};close.closed.toCompletableFuture().get(2,TimeUnit.SECONDS);assertEquals(1,count.get())}
    }
    @Test fun neverStartedWriterCanDrainWithoutClosingUnopenedBackend(){
        val backend=InMemoryStorageBackend();val pipeline=BufferedLogPipeline(backend,4,2,10,closeStorageOnStop=false)
        assertTrue(pipeline.stopGracefully(Duration.ofSeconds(1)));pipeline.drained.toCompletableFuture().get(1,TimeUnit.SECONDS)
    }
}
