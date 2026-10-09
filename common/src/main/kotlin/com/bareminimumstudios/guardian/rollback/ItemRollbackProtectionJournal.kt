package com.bareminimumstudios.guardian.rollback

import java.util.UUID
import com.bareminimumstudios.guardian.domain.InventorySnapshot

/**
 * Optional durable protection capability, to be registered and confirmed before any writes.
 * Protection preserves owner claims and recovery visibility even after a COMPLETED transition.
 * It is not inventory exclusion, a saved-state proof or authority to replay/release a journal.
 * Reconciliation requires complete immutable images and a trusted quiescent saved-state proof.
 */
interface ItemRollbackProtectionJournal : ItemRollbackJournal {
    /** Exact, bounded PREPARED record and all existing owner/source claims must match atomically. */
    fun protectItemRollback(record: ItemRollbackRecord): Boolean
    fun itemRollbackProtected(operationId: UUID): Boolean
    /** Complete images must be registered atomically while the exact record is still PREPARED. */
    fun protectItemRollback(record: ItemRollbackRecord, original: InventorySnapshot): Boolean
    fun itemRollbackImages(operationId: UUID): ItemRollbackImageRecord?
    /** Trusted caller must exclude mutations and drain prior journal/disk work before sampling. */
    fun acknowledgeItemRollback(record: ItemRollbackRecord, images: ItemRollbackImages, saved: InventorySnapshot): Boolean
}
