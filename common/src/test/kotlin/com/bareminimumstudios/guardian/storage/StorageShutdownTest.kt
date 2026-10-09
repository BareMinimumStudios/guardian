package com.bareminimumstudios.guardian.storage
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
class StorageShutdownTest {
    @Test fun waitsForEveryActualDrainAndClosesOffCallerThread(){
        val caller=Thread.currentThread();val a=CompletableFuture<Void>();val b=CompletableFuture<Void>();val count=AtomicInteger()
        val close=StorageShutdown(listOf(a.minimalCompletionStage(),b.minimalCompletionStage())){assertNotSame(caller,Thread.currentThread());count.incrementAndGet()}
        a.complete(null);assertFalse(close.closed.toCompletableFuture().isDone);assertEquals(0,count.get());b.complete(null);close.closed.toCompletableFuture().get(2,TimeUnit.SECONDS);assertEquals(1,count.get())
    }
    @Test fun failedDrainCannotCloseBackend(){val a=CompletableFuture<Void>();val count=AtomicInteger();val close=StorageShutdown(listOf(a.minimalCompletionStage())){count.incrementAndGet()};a.completeExceptionally(IllegalStateException("Not drained"));assertFailsWith<ExecutionException>{close.closed.toCompletableFuture().get()};assertEquals(0,count.get())}
    @Test fun cancelledActualDrainCannotCloseBackend(){val a=CompletableFuture<Void>();val count=AtomicInteger();val close=StorageShutdown(listOf(a.minimalCompletionStage())){count.incrementAndGet()};a.cancel(false);assertFailsWith<ExecutionException>{close.closed.toCompletableFuture().get()};assertEquals(0,count.get())}
    @Test fun cancelledClosureObserverCannotMaskRealClosure(){val a=CompletableFuture<Void>();val count=AtomicInteger();val close=StorageShutdown(listOf(a.minimalCompletionStage())){count.incrementAndGet()};close.closed.toCompletableFuture().cancel(false);a.complete(null);close.closed.toCompletableFuture().get(2,TimeUnit.SECONDS);assertEquals(1,count.get())}
    @Test fun closureFailureIsReportedWithoutRetry(){val count=AtomicInteger();val close=StorageShutdown(listOf(CompletableFuture.completedFuture(null))){count.incrementAndGet();error("Close failed")};assertFailsWith<ExecutionException>{close.closed.toCompletableFuture().get(2,TimeUnit.SECONDS)};assertEquals(1,count.get())}
}
