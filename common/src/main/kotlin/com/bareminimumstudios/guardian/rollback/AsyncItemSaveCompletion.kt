package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

interface ItemSaveJournalPort {
    fun read(operationId: UUID): CompletionStage<ItemRollbackRecord?>
    /** Compare the full record again before the phase CAS. Only call after verified saves. */
    fun complete(record: ItemRollbackRecord): CompletionStage<Boolean>
}

/** The executor must be bounded and reject rather than run work on its submitting thread. */
class AsyncItemSaveJournal(private val journal: ItemRollbackJournal, private val executor: Executor) : ItemSaveJournalPort {
    private val thread = Thread.currentThread()
    override fun read(operationId: UUID): CompletionStage<ItemRollbackRecord?> = submit { journal.itemRollback(operationId) }
    override fun complete(record: ItemRollbackRecord): CompletionStage<Boolean> {
        require(record.phase == ItemRollbackPhase.APPLYING || record.phase == ItemRollbackPhase.RECOVERY_REQUIRED)
        return submit {
            sameSaveRecord(record, journal.itemRollback(record.operationId)) &&
                journal.transitionItemRollback(record.operationId, record.phase, ItemRollbackPhase.COMPLETED)
        }
    }
    private fun <T> submit(action: () -> T): CompletableFuture<T> {
        check(Thread.currentThread() === thread) { "Journal requests must use their owning driver thread" }
        return try {
            CompletableFuture.supplyAsync({
                check(Thread.currentThread() !== thread) { "Journal I/O cannot run on the live inventory thread" }
                action()
            }, executor)
        } catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
    }
}

enum class AsyncItemSaveState { CHECKING_JOURNAL, SAVING, CHECKING_FINAL_JOURNAL, COMMITTING, CONFIRMING, COMPLETED, UNRESOLVED }

/**
 * Inventory calls stay on the creating thread; journal work uses an asynchronous port.
 * A trusted host must retain exclusive coordination until any submitted completion settles,
 * including after stop/timeout. Stopping cannot undo a commit already submitted to the worker.
 * This driver neither releases leases nor implements Minecraft mutation exclusion.
 */
class AsyncItemSaveCompletion(
    private val record: ItemRollbackRecord,
    private val journal: ItemSaveJournalPort,
    private val port: ItemSavePort,
    private val clock: () -> Long = System::nanoTime
) {
    private val thread = Thread.currentThread()
    private val check = ItemRecoveryCheck(record)
    private val owners = check.owners.toSet()
    private val expected = linkedMapOf<ItemSlotAddress, ItemStackSnapshot>().apply {
        record.entries.forEach { entry -> entry.changes.forEach { put(it.address, it.before) } }
    }
    private val started = clock()
    private var index = 0
    private var read: CompletableFuture<ItemRollbackRecord?>? = null
    private var saved: CompletableFuture<InventorySnapshot?>? = null
    private var commit: CompletableFuture<Boolean>? = null
    /** Host can drain this result after stopping. A failed acknowledgement can still hide a committed write. */
    val completionAttempt: CompletionStage<Boolean>? get() = commit?.minimalCompletionStage()
    var state = AsyncItemSaveState.CHECKING_JOURNAL; private set
    var reason: String? = null; private set
    init { require(record.phase == ItemRollbackPhase.APPLYING || record.phase == ItemRollbackPhase.RECOVERY_REQUIRED) }

    fun advance(): AsyncItemSaveState {
        check(Thread.currentThread() === thread) { "Save completion must use one serialized driver thread" }
        if (state == AsyncItemSaveState.COMPLETED || state == AsyncItemSaveState.UNRESOLVED) return state
        try {
            if (clock() - started >= TimeUnit.SECONDS.toNanos(10)) return refuse("Save completion timed out")
            if (!port.isExclusiveAndCurrent(owners)) return refuse("Exclusive ownership, identity or restored contents changed")
            when (state) {
                AsyncItemSaveState.CHECKING_JOURNAL, AsyncItemSaveState.CHECKING_FINAL_JOURNAL, AsyncItemSaveState.CONFIRMING -> {
                    if (read == null) { read = journal.read(record.operationId).toCompletableFuture(); return state }
                    if (!read!!.isDone) return state
                    val phase = if (state == AsyncItemSaveState.CONFIRMING) ItemRollbackPhase.COMPLETED else record.phase
                    if (!sameSaveRecord(record, read!!.join(), phase)) return refuse("Journal record changed")
                    read = null
                    when (state) {
                        AsyncItemSaveState.CONFIRMING -> state = AsyncItemSaveState.COMPLETED
                        AsyncItemSaveState.CHECKING_FINAL_JOURNAL -> {
                            if (!port.isExclusiveAndCurrent(owners)) return refuse("Ownership changed before completion")
                            commit = journal.complete(record).toCompletableFuture()
                            state = AsyncItemSaveState.COMMITTING
                        }
                        else -> state = AsyncItemSaveState.SAVING
                    }
                }
                AsyncItemSaveState.SAVING -> {
                    val owner = check.owners[index]
                    if (saved == null) { saved = port.saveAndReadBack(owner, check.addresses(owner)).toCompletableFuture(); return state }
                    if (!saved!!.isDone) return state
                    val snapshot = saved!!.join() ?: return refuse("Saved inventory is unavailable")
                    val wanted = check.addresses(owner)
                    val bytes = wanted.sumOf { (snapshot.slots[it]?.itemData?.size ?: 0).toLong() }
                    if (bytes > 16L * 1024 * 1024 || wanted.any { snapshot.slots[it] != expected[it] })
                        return refuse("Saved slots do not match the restored state")
                    saved = null
                    index++
                    state = if (index == check.owners.size) AsyncItemSaveState.CHECKING_FINAL_JOURNAL else AsyncItemSaveState.CHECKING_JOURNAL
                }
                AsyncItemSaveState.COMMITTING -> {
                    if (!commit!!.isDone) return state
                    if (!commit!!.join()) return refuse("Journal completion was not acknowledged")
                    state = AsyncItemSaveState.CONFIRMING
                }
                else -> error("Unexpected save completion state")
            }
        } catch (_: Exception) { return refuse("Save or journal operation failed") }
        return state
    }
    fun stop(): AsyncItemSaveState {
        check(Thread.currentThread() === thread)
        if (state != AsyncItemSaveState.COMPLETED && state != AsyncItemSaveState.UNRESOLVED) refuse("Save completion stopped")
        return state
    }
    private fun refuse(message: String): AsyncItemSaveState {
        reason = if (commit == null) message else "$message; completion was submitted and its persistent outcome must be reconciled"
        state = AsyncItemSaveState.UNRESOLVED
        return state
    }
}

internal fun sameSaveRecord(expected: ItemRollbackRecord, actual: ItemRollbackRecord?, phase: ItemRollbackPhase = expected.phase): Boolean =
    actual != null && actual.operationId == expected.operationId && actual.createdAt == expected.createdAt && actual.phase == phase &&
        actual.entries.size == expected.entries.size && actual.entries.zip(expected.entries).all { (a, b) -> a.transactionId == b.transactionId && a.changes == b.changes }
