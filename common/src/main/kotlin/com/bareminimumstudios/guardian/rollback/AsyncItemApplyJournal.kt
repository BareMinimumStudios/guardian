package com.bareminimumstudios.guardian.rollback

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

/** Use a platform-owned bounded worker executor; an inline executor is refused before journal access. */
class AsyncItemApplyJournal(private val journal: ItemRollbackJournal, private val executor: Executor) : ItemApplyJournalPort {
    private val thread = Thread.currentThread()

    override fun read(operationId: UUID): CompletionStage<ItemRollbackRecord?> = submit { journal.itemRollback(operationId) }

    override fun markApplying(operationId: UUID): CompletionStage<Boolean> = submit {
        journal.transitionItemRollback(operationId, ItemRollbackPhase.PREPARED, ItemRollbackPhase.APPLYING)
    }

    private fun <T> submit(action: () -> T): CompletableFuture<T> {
        check(Thread.currentThread() === thread) { "Journal requests must use their owning driver thread" }
        return try {
            CompletableFuture.supplyAsync({
                check(Thread.currentThread() !== thread) { "Journal I/O cannot run on the live inventory thread" }
                action()
            }, executor)
        } catch (failure: Exception) {
            CompletableFuture.failedFuture(failure)
        }
    }
}
