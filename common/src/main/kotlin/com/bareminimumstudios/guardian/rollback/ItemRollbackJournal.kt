package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.checkpoint.CheckpointRequester
import com.bareminimumstudios.guardian.checkpoint.CheckpointRestoreLink
import com.bareminimumstudios.guardian.checkpoint.CheckpointRestoreSource
import com.bareminimumstudios.guardian.checkpoint.InventoryCheckpoint
import com.bareminimumstudios.guardian.checkpoint.InventoryCheckpointLimits
import com.bareminimumstudios.guardian.domain.*
import java.util.Collections
import java.util.UUID

enum class ItemRollbackPhase { PREPARED, APPLYING, RECOVERY_REQUIRED, COMPLETED, CANCELLED }

data class ItemRollbackSummary(val operationId: UUID, val createdAt: Long, val phase: ItemRollbackPhase)

class ItemRollbackRecord @JvmOverloads constructor(val operationId: UUID, val createdAt: Long, val phase: ItemRollbackPhase, entries: List<ItemRollbackEntry>, val purpose: ItemRecoveryPurpose = ItemRecoveryPurpose.TRANSFER_ROLLBACK, val redoOf: UUID? = null, val checkpointOf: UUID? = null) {
    init { require((purpose == ItemRecoveryPurpose.TRANSFER_REDO) == (redoOf != null)); require(redoOf != operationId); require((purpose == ItemRecoveryPurpose.CHECKPOINT_RESTORE) == (checkpointOf != null)); require(checkpointOf != operationId) }
    val entries: List<ItemRollbackEntry> = Collections.unmodifiableList(ArrayList(entries))
}

class ItemRollbackEntry(val transactionId: UUID, changes: List<ItemSlotChange>) {
    val changes: List<ItemSlotChange> = Collections.unmodifiableList(ArrayList(changes))
}

/** Persistent journal only. No implementation may mutate Minecraft inventories. */
interface ItemRollbackJournal {
    fun prepareItemRollback(operationId: UUID, createdAt: Long, newestFirst: List<ContainerTransactionSnapshot>): ItemRollbackRecord
    fun prepareCreativeRecovery(operationId: UUID, createdAt: Long, newestFirst: List<ContainerTransactionSnapshot>): ItemRollbackRecord = throw UnsupportedOperationException("Creative item recovery is unavailable")
    fun restorableItemRollbacks(limit: Int = 10): List<ItemRollbackSummary> = emptyList()
    fun itemRestoreSource(operationId: UUID): ItemRestoreSource = throw UnsupportedOperationException("Item redo is unavailable")
    fun prepareItemRestore(operationId: UUID, createdAt: Long, sourceOperation: UUID): ItemRollbackRecord = throw UnsupportedOperationException("Item redo is unavailable")
    fun checkpointRestoreSource(id: UUID): CheckpointRestoreSource = throw UnsupportedOperationException("Checkpoint restore is unavailable")
    fun checkpointRestoreLink(id: UUID): CheckpointRestoreLink? = null
    fun prepareCheckpointRestore(id: UUID, time: Long, checkpoint: UUID, before: InventoryCheckpoint, requester: CheckpointRequester, limits: InventoryCheckpointLimits): ItemRollbackRecord = throw UnsupportedOperationException("Checkpoint restore is unavailable")
    fun itemRollback(operationId: UUID): ItemRollbackRecord?
    /** Headers only, including protected COMPLETED records still awaiting reconciliation. No payload bulk load. */
    fun unfinishedItemRollbacks(limit: Int = 50): List<ItemRollbackSummary>
    fun transitionItemRollback(operationId: UUID, expected: ItemRollbackPhase, next: ItemRollbackPhase): Boolean
}

enum class ItemRecoveryObservation { ORIGINAL, RESTORED, BOTH, PARTIAL, CONFLICT, UNAVAILABLE }

/** Observation only: never replay an interrupted operation based on these labels. */
object ItemRollbackRecovery {
    fun observe(record: ItemRollbackRecord, live: InventorySnapshot): ItemRecoveryObservation {
        require(record.entries.isNotEmpty())
        val original = linkedMapOf<ItemSlotAddress, ItemStackSnapshot>()
        val restored = linkedMapOf<ItemSlotAddress, ItemStackSnapshot>()
        for (entry in record.entries) for (change in entry.changes) {
            original.putIfAbsent(change.address, change.after)
            restored[change.address] = change.before
        }
        if (original.keys.any { it !in live.slots }) return ItemRecoveryObservation.UNAVAILABLE
        val isOriginal = original.all { (address, item) -> live.slots[address] == item }
        val isRestored = restored.all { (address, item) -> live.slots[address] == item }
        if (isOriginal && isRestored) return ItemRecoveryObservation.BOTH
        if (isOriginal) return ItemRecoveryObservation.ORIGINAL
        if (isRestored) return ItemRecoveryObservation.RESTORED
        if (original.any { (address, item) -> live.slots[address] != item && live.slots[address] != restored[address] }) return ItemRecoveryObservation.CONFLICT
        return ItemRecoveryObservation.PARTIAL
    }
}
