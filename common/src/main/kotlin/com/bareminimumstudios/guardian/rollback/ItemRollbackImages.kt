package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*

/** Complete platform-supplied owner images. Contiguous slots do not establish platform exclusion. */
class ItemRollbackImages(val record: ItemRollbackRecord, original: InventorySnapshot) {
    val original = InventorySnapshot(original.slots)
    val expected: InventorySnapshot
    init {
        require(record.createdAt >= 0)
        val owners=ItemRecoveryCheck(record).owners.toSet()
        require(original.slots.size in 1..2048 && original.slots.keys.map { it.owner }.toSet()==owners)
        owners.forEach { owner ->
            val indices=original.slots.keys.filter { it.owner==owner }.map { it.index }.sorted()
            require(indices.size in 1..256 && indices == indices.indices.toList()) { "Owner image must contain every contiguous slot" }
            if(owner is ItemSlotOwner.PlayerInventory) require(indices.size==41) { "Player image requires all 41 inventory slots" }
        }
        val virtual=original.slots.toMutableMap()
        record.entries.forEach { entry -> entry.changes.forEach { change ->
            require(virtual[change.address]==change.after) { "Original image does not match the complete reverse chain" }
            virtual[change.address]=change.before
        } }
        expected=InventorySnapshot(virtual)
        require((original.slots.values+expected.slots.values).sumOf { (it.itemData?.size ?: 0).toLong() } <= MAX_BYTES)
    }
    companion object { const val MAX_BYTES = 16L * 1024 * 1024 }
}

class ItemRollbackImageRecord(val images: ItemRollbackImages, val acknowledged: Boolean)

internal fun sameRollbackImages(first: ItemRollbackImages, second: ItemRollbackImages): Boolean =
    sameSaveRecord(first.record,second.record,second.record.phase) &&
        first.original.slots==second.original.slots && first.expected.slots==second.expected.slots
