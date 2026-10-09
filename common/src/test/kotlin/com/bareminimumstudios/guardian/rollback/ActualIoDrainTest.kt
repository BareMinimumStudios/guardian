package com.bareminimumstudios.guardian.rollback
import java.util.concurrent.*
import kotlin.test.*
class ActualIoDrainTest {
    @Test fun admissionMustCloseBeforeEmptyWorkIsDrained(){val work=ActualIoDrain();assertFalse(work.isDrained);assertFalse(work.drained.toCompletableFuture().isDone);work.close();assertTrue(work.isDrained)}
    @Test fun allActualTicketsMustFinishAfterClose(){val work=ActualIoDrain();val a=work.begin();val b=work.begin();work.close();a.close();assertFalse(work.isDrained);b.close();assertTrue(work.isDrained);work.drained.toCompletableFuture().get()}
    @Test fun observerCancellationCannotMaskOutstandingWork(){val work=ActualIoDrain();val ticket=work.begin();work.close();work.drained.toCompletableFuture().cancel(false);assertFalse(work.isDrained);ticket.close();work.drained.toCompletableFuture().get()}
    @Test fun ticketCompletionIsIdempotentAndCrossThread(){val work=ActualIoDrain();val ticket=work.begin();work.close();CompletableFuture.runAsync{repeat(20){ticket.close()}}.get();assertTrue(work.isDrained);ticket.close()}
    @Test fun boundsAndStoppedAdmissionAreEnforced(){assertFailsWith<IllegalArgumentException>{ActualIoDrain(0)};val work=ActualIoDrain(1);val ticket=work.begin();assertFailsWith<IllegalStateException>{work.begin()};work.close();assertFailsWith<IllegalStateException>{work.begin()};ticket.close()}
    @Test fun prefixFenceWaitsForOldWorkWithoutClosingNewReadAdmission(){val work=ActualIoDrain();val old=work.begin();val fence=work.fence();val next=work.begin();assertFalse(fence.toCompletableFuture().isDone);old.close();fence.toCompletableFuture().get();assertFalse(work.isDrained);work.close();assertFalse(work.drained.toCompletableFuture().isDone);next.close();work.drained.toCompletableFuture().get()}
    @Test fun cancelledFenceObserverCannotMaskOldWork(){val work=ActualIoDrain();val ticket=work.begin();val fence=work.fence();fence.toCompletableFuture().cancel(false);assertFalse(work.fence().toCompletableFuture().isDone);ticket.close();fence.toCompletableFuture().get();work.close()}
    @Test fun pendingFencesAreBoundedAndEmptyFenceIsImmediate(){val work=ActualIoDrain();work.fence().toCompletableFuture().get();val ticket=work.begin();val fences=(1..32).map{work.fence()};assertFailsWith<IllegalStateException>{work.fence()};ticket.close();fences.forEach{it.toCompletableFuture().get()};work.fence().toCompletableFuture().get();work.close()}

}
