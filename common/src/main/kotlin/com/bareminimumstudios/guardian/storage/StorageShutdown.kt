package com.bareminimumstudios.guardian.storage

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** One closure after trusted actual drains. Failed drain cannot authorize backend closure. */
class StorageShutdown(actualDrains: List<CompletionStage<Void>>, closeBackend: () -> Unit) {
    private val completion = CompletableFuture<Void>()
    val closed: CompletionStage<Void> get() = completion.minimalCompletionStage()
    init {
        require(actualDrains.size in 1..32)
        val snapshots = actualDrains.map { it.thenApply { value -> value }.toCompletableFuture() }
        CompletableFuture.allOf(*snapshots.toTypedArray()).whenComplete { _: Void?, error: Throwable? ->
            if(error != null) completion.completeExceptionally(error)
            else Thread({
                try {closeBackend();completion.complete(null)}
                catch(failure: Throwable){completion.completeExceptionally(failure)}
            }, "Guardian-StorageShutdown").apply {isDaemon=true}.start()
        }
    }
}
