package com.bareminimumstudios.guardian.rollback

import java.util.UUID

/**
 * Optional durable protection capability, to be registered and confirmed before any writes.
 * Protection preserves owner claims and recovery visibility even after a COMPLETED transition.
 * It is not inventory exclusion, a saved-state proof or authority to replay/release a journal.
 * There is deliberately no release API until full saved-owner reconciliation is available.
 */
interface ItemRollbackProtectionJournal : ItemRollbackJournal {
    /** Exact, bounded PREPARED record and all existing owner/source claims must match atomically. */
    fun protectItemRollback(record: ItemRollbackRecord): Boolean
    fun itemRollbackProtected(operationId: UUID): Boolean
}
