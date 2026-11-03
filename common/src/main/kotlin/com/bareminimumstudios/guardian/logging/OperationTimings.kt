package com.bareminimumstudios.guardian.logging

import java.util.concurrent.atomic.AtomicLong

/** Fixed content-free counters. Never records players, inventory contents, SQL or filters. */
object OperationTimings {
    enum class Stage { TRANSACTION_QUEUE, TRANSACTION_QUERY, TRANSACTION_DELIVERY, HISTORY_GUARD_QUEUE, HISTORY_GUARD_QUERY, ROLLBACK_FENCE, ROLLBACK_PREPARE, ROLLBACK_APPLY_SAVE, ROLLBACK_PREVIEW_TOTAL, ROLLBACK_CONFIRM_TOTAL }
    data class Sample(val count:Long,val lastMillis:Long,val maxMillis:Long)
    private class Counter { val count=AtomicLong();val last=AtomicLong();val max=AtomicLong() }
    private val counters=Stage.entries.associateWith { Counter() }
    fun record(stage:Stage,nanos:Long) { val c=counters.getValue(stage);val n=nanos.coerceAtLeast(0);c.last.set(n);c.max.accumulateAndGet(n,::maxOf);c.count.incrementAndGet() }
    fun sample(stage:Stage):Sample { val c=counters.getValue(stage);return Sample(c.count.get(),c.last.get()/1_000_000,c.max.get()/1_000_000) }
    fun status():List<String> = Stage.entries.mapNotNull { stage->val s=sample(stage);if(s.count==0L)null else "Guardian timing "+stage.name.lowercase()+": last="+s.lastMillis+"ms max="+s.maxMillis+"ms count="+s.count }
}
