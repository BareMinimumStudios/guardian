package com.bareminimumstudios.guardian.logging
import kotlin.test.*
class OperationTimingsTest {
    @Test fun countersClampElapsedTimeAndRetainMaximum() {
        val stage=OperationTimings.Stage.TRANSACTION_DELIVERY;val before=OperationTimings.sample(stage)
        OperationTimings.record(stage,5_000_000);OperationTimings.record(stage,-1)
        val result=OperationTimings.sample(stage)
        assertEquals(before.count+2,result.count);assertEquals(0,result.lastMillis);assertTrue(result.maxMillis>=5)
    }
    @Test fun parallelRecordingKeepsExactCountsAndFixedLabels() {
        val stage=OperationTimings.Stage.HISTORY_GUARD_QUEUE;val before=OperationTimings.sample(stage)
        val workers=List(4) { Thread { repeat(500) { OperationTimings.record(stage,2_000_000) } }.apply { start() } }
        workers.forEach { it.join() }
        assertEquals(before.count+2000,OperationTimings.sample(stage).count)
        assertTrue(OperationTimings.status().any { "history_guard_queue" in it })
        assertTrue(OperationTimings.status().size<=OperationTimings.Stage.entries.size)
    }
}
