package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID

/** A completed, saved transfer rollback is the sole authority for its inverse operation. */
class ItemRestoreSource(val images: ItemRollbackImages, val guard: ContainerHistoryGuard) {
    init {
        require(images.record.phase == ItemRollbackPhase.COMPLETED)
        require(images.record.purpose == ItemRecoveryPurpose.TRANSFER_ROLLBACK && images.record.redoOf == null)
    }

    fun record(id: UUID, time: Long): ItemRollbackRecord {
        require(id != images.record.operationId && time >= images.record.createdAt)
        val entries = images.record.entries.asReversed().map { entry ->
            ItemRollbackEntry(entry.transactionId, entry.changes.map { ItemSlotChange(it.address, it.after, it.before) })
        }
        return ItemRollbackRecord(id, time, ItemRollbackPhase.PREPARED, entries,
            ItemRecoveryPurpose.TRANSFER_REDO, images.record.operationId)
    }

    /** Every recorded owner slot must still match the saved result of the original rollback. */
    fun preview(live: InventorySnapshot, unavailable: Set<ItemSlotOwner> = emptySet()): ContainerRollbackPreview {
        val reason = when {
            guard.incompleteTransactions.isNotEmpty() -> ContainerPreviewReason.INCOMPLETE_HISTORY
            guard.claimedTransactions.isNotEmpty() -> ContainerPreviewReason.CLAIMED_SOURCE
            guard.reservedOwners.isNotEmpty() -> ContainerPreviewReason.RESERVED_OWNER
            guard.changedBlocks.isNotEmpty() -> ContainerPreviewReason.CHANGED_BLOCK
            guard.newerOwners.isNotEmpty() -> ContainerPreviewReason.NEWER_HISTORY
            unavailable.isNotEmpty() || images.expected.slots.keys.any { it !in live.slots } -> ContainerPreviewReason.UNAVAILABLE_OWNER
            live.slots != images.expected.slots -> ContainerPreviewReason.STATE_MISMATCH
            else -> ContainerPreviewReason.ELIGIBLE
        }
        return ContainerRollbackPreview(images.record.entries.map { ContainerPreviewEntry(it.transactionId, reason, it.changes.size) })
    }
}

/** An expected refusal is reported to the requester, not logged as a storage failure. */
open class ItemRecoveryRefusedException(message: String) : IllegalArgumentException(message)
class ItemRestoreRefusedException(message: String) : ItemRecoveryRefusedException(message)
