package com.bareminimumstudios.guardian.logging

import com.bareminimumstudios.guardian.domain.ItemSlotOwner

/** Captured audit attempts invalidate sampled owners. This never blocks gameplay or certifies saves. */
class AuditOwnerObservation internal constructor(private val changed: () -> Set<ItemSlotOwner>,private val release: () -> Unit): AutoCloseable {
    fun changedOwners(): Set<ItemSlotOwner> = changed()
    override fun close() = release()
}
