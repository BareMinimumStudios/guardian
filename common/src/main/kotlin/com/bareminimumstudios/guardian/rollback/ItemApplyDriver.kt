package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/** Journal operations must finish on a worker, never block the live inventory thread. */
interface ItemApplyJournalPort {
    fun read(operationId: UUID): CompletionStage<ItemRollbackRecord?>
    fun markApplying(operationId: UUID): CompletionStage<Boolean>
}

/** Trusted platform contract. Reservations alone do not establish exclusive ownership. */
interface ItemApplyPort {
    fun isExclusiveAndCurrent(owners: Set<ItemSlotOwner>): Boolean
    /** Complete images of all owners, including unchanged slots; null means unavailable. */
    fun readOwners(owners: Set<ItemSlotOwner>): InventorySnapshot?
    /** An audited synchronous setter. It may have changed contents even if it throws. */
    fun writeSlot(address: ItemSlotAddress, expected: ItemStackSnapshot, replacement: ItemStackSnapshot)
}

enum class ItemApplyState { CHECKING_PREPARED, MARKING_APPLYING, CHECKING_APPLYING, WRITING, CHECKING_FINAL_JOURNAL, WRITTEN, UNRESOLVED }

/**
 * Internal serialized apply protocol. Each advance polls or writes at most one slot.
 * WRITTEN is not saved or COMPLETED. Claims and APPLYING remain until verified saves.
 * Failure/stop never cancels a journal future, clears claims or replays partial writes.
 */
class ItemApplyDriver(
    private val record: ItemRollbackRecord,
    private val journal: ItemApplyJournalPort,
    private val port: ItemApplyPort,
    private val clock: () -> Long = System::nanoTime
) {
    private val thread = Thread.currentThread()
    private val started = clock()
    private val owners = ItemRecoveryCheck(record).owners.toSet()
    private val original = linkedMapOf<ItemSlotAddress, ItemStackSnapshot>()
    private val restored = linkedMapOf<ItemSlotAddress, ItemStackSnapshot>()
    private val writes: List<ItemSlotChange>
    private var virtual: MutableMap<ItemSlotAddress, ItemStackSnapshot>? = null
    private var read: CompletableFuture<ItemRollbackRecord?>? = null
    private var marking: CompletableFuture<Boolean>? = null
    private var index = 0
    var attemptedSlots = 0; private set
    var state = ItemApplyState.CHECKING_PREPARED; private set
    var reason: String? = null; private set
    var applyingRecord: ItemRollbackRecord? = null; private set

    init {
        require(record.phase == ItemRollbackPhase.PREPARED)
        record.entries.forEach { entry -> entry.changes.forEach {
            original.putIfAbsent(it.address, it.after)
            restored[it.address] = it.before
        } }
        writes = original.mapNotNull { (address, before) ->
            val after = restored.getValue(address)
            if (before == after) null else ItemSlotChange(address, before, after)
        }
    }

    fun advance(): ItemApplyState {
        checkThread()
        if (state == ItemApplyState.WRITTEN || state == ItemApplyState.UNRESOLVED) return state
        try {
            if (clock() - started >= TimeUnit.SECONDS.toNanos(10)) return refuse("Item apply timed out")
            if (!port.isExclusiveAndCurrent(owners)) return refuse("Exclusive ownership or inventory identity changed")
            val live = port.readOwners(owners) ?: return refuse("Complete live inventories are unavailable")
            if (live.slots.size > 2048 || live.slots.keys.map { it.owner }.toSet() != owners ||
                live.slots.values.sumOf { (it.itemData?.size ?: 0).toLong() } > 16L * 1024 * 1024) {
                return refuse("Live inventory image exceeds its owner or payload budget")
            }
            if (virtual == null) {
                if (original.any { (address, item) -> live.slots[address] != item }) return refuse("Original item contents changed")
                virtual = live.slots.toMutableMap()
            } else if (live.slots != virtual) return refuse("Full live inventory image changed")
            if (!port.isExclusiveAndCurrent(owners)) return refuse("Ownership changed while reading live inventories")

            when (state) {
                ItemApplyState.CHECKING_PREPARED -> {
                    val value = pollRead() ?: return state
                    if (!sameJournal(value, ItemRollbackPhase.PREPARED)) return refuse("Prepared journal changed")
                    read = null
                    marking = journal.markApplying(record.operationId).toCompletableFuture()
                    state = ItemApplyState.MARKING_APPLYING
                }
                ItemApplyState.MARKING_APPLYING -> {
                    val pending = checkNotNull(marking)
                    if (!pending.isDone) return state
                    if (!pending.join()) return refuse("Applying intent was not confirmed")
                    marking = null
                    state = ItemApplyState.CHECKING_APPLYING
                }
                ItemApplyState.CHECKING_APPLYING, ItemApplyState.CHECKING_FINAL_JOURNAL -> {
                    val value = pollRead() ?: return state
                    if (!sameJournal(value, ItemRollbackPhase.APPLYING)) return refuse("Applying journal changed")
                    read = null
                    val finished = state == ItemApplyState.CHECKING_FINAL_JOURNAL
                    if (finished) applyingRecord = value
                    state = if (finished) ItemApplyState.WRITTEN else ItemApplyState.WRITING
                }
                ItemApplyState.WRITING -> {
                    if (index < writes.size) {
                        val change = writes[index]
                        attemptedSlots++
                        port.writeSlot(change.address, change.before, change.after)
                        virtual!![change.address] = change.after
                        index++
                        if (!port.isExclusiveAndCurrent(owners)) return refuse("Ownership changed during a setter")
                        if (port.readOwners(owners)?.slots != virtual) return refuse("Setter changed the full inventory image unexpectedly")
                        if (!port.isExclusiveAndCurrent(owners)) return refuse("Ownership changed while checking a setter")
                    }
                    if (index == writes.size) state = ItemApplyState.CHECKING_FINAL_JOURNAL
                }
                else -> error("Unexpected item apply state")
            }
        } catch (_: Exception) {
            return refuse("Journal, inventory read or setter failed; recovery may be required")
        }
        return state
    }

    fun stop(): ItemApplyState {
        checkThread()
        if (state != ItemApplyState.WRITTEN && state != ItemApplyState.UNRESOLVED) refuse("Item apply stopped")
        return state
    }

    private fun pollRead(): ItemRollbackRecord? {
        if (read == null) {
            read = journal.read(record.operationId).toCompletableFuture()
            return null
        }
        val pending = checkNotNull(read)
        if (!pending.isDone) return null
        return pending.join() ?: throw IllegalStateException("Item journal is unavailable")
    }

    private fun sameJournal(other: ItemRollbackRecord, phase: ItemRollbackPhase): Boolean =
        other.operationId == record.operationId && other.createdAt == record.createdAt && other.phase == phase &&
            other.entries.size == record.entries.size && other.entries.zip(record.entries).all { (a, b) ->
                a.transactionId == b.transactionId && a.changes == b.changes
            }

    private fun refuse(message: String): ItemApplyState {
        read = null
        marking = null
        reason = message
        state = ItemApplyState.UNRESOLVED
        return state
    }

    private fun checkThread() = check(Thread.currentThread() === thread) { "Item apply must use its serialized driver thread" }
}
