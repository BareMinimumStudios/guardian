package com.bareminimumstudios.guardian.logging

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

data class AuditWriteFence(val acceptedThrough: Long, val persistedThrough: Long)

/** Only the pipeline completes this receipt. Callbacks must marshal work to their own executor. */
class AuditWriteBarrier internal constructor(
    val acceptedThrough: Long,
    private val completion: CompletableFuture<AuditWriteFence>,
    private val cancelAction: () -> Unit
) {
    val result: CompletionStage<AuditWriteFence> = completion.minimalCompletionStage()
    fun cancel() = cancelAction()
}
