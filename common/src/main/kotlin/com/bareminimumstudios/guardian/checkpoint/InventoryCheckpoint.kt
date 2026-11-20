package com.bareminimumstudios.guardian.checkpoint

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID

enum class InventoryCheckpointReason { MANUAL, PERIODIC, JOIN, LOGOUT, DEATH, BEFORE_RESTORE }

/** Complete persistent player slots; no cursor, crafting, ender chest or mod-owned storage. */
class InventoryCheckpoint(
    val checkpointId: UUID,
    val playerId: UUID,
    val playerName: String,
    val capturedAt: Long,
    val minecraftVersion: String,
    val reason: InventoryCheckpointReason,
    val dimension: ResourceId,
    val position: BlockPosition,
    inventory: InventorySnapshot
) {
    val inventory = InventorySnapshot(inventory.slots)

    init {
        require(capturedAt >= 0)
        require(playerName.length in 1..64 && playerName.none(Char::isISOControl))
        require(minecraftVersion.length in 1..64 && minecraftVersion.none(Char::isISOControl))
        val owner = ItemSlotOwner.PlayerInventory(playerId)
        require(this.inventory.slots.size in setOf(41,43))
        require(this.inventory.slots.keys.all { it.owner == owner })
        require(this.inventory.slots.keys.map { it.index }.sorted() == (0 until this.inventory.slots.size).toList())
        require(this.inventory.slots.values.all { it.isEmpty || it.itemData != null && it.itemData.size > 0 })
        require(this.inventory.slots.values.sumOf { (it.itemData?.size ?: 0).toLong() } <= MAX_COMPONENT_BYTES)
    }

    /** The snapshot is explicit creation/replacement authority, never a conserving transfer source. */
    fun replacementChanges(current: InventorySnapshot): List<ItemSlotChange> {
        require(current.slots.keys == inventory.slots.keys) { "Current inventory shape differs from checkpoint" }
        return inventory.slots.entries.sortedBy { it.key.index }.mapNotNull { (address, target) ->
            val observed = current.slots.getValue(address)
            if(observed == target) null else ItemSlotChange(address,target,observed)
        }
    }

    companion object { const val MAX_COMPONENT_BYTES = 1024L * 1024 }
}
