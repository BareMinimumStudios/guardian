package com.bareminimumstudios.guardian.rollback

class ItemRollbackStartupRefused(message: String) : IllegalStateException(message)

/** Never resume gameplay with an interrupted multi-owner save or unacknowledged item receipt. */
object ItemRollbackStartupGuard {
    fun verify(journal: ItemRollbackJournal) {
        val pending = journal.unfinishedItemRollbacks(100)
        if (pending.isEmpty()) return
        // No inventory writer is admitted until protected images exist and APPLYING is durable.
        // An unprotected PREPARED header can therefore be cancelled without touching item data.
        pending.forEach { header ->
            if (header.phase == ItemRollbackPhase.PREPARED && journal is ItemRollbackProtectionJournal &&
                !journal.itemRollbackProtected(header.operationId)) {
                check(journal.transitionItemRollback(header.operationId,ItemRollbackPhase.PREPARED,ItemRollbackPhase.CANCELLED)) {
                    "Unstarted item admission could not be cancelled"
                }
            }
        }
        val unresolved = journal.unfinishedItemRollbacks(1).firstOrNull() ?: return
        throw ItemRollbackStartupRefused("Guardian cannot resume gameplay: unfinished item rollback ${unresolved.operationId} (${unresolved.phase}). " +
            "Keep guardian.sqlite and the world/player data together. Stop the server and preview targeted recovery with -Dguardian.itemRecovery=preview:${unresolved.operationId}; see docs/ITEM_RECOVERY.md. Do not delete journal rows.")
    }
}
