package com.bareminimumstudios.guardian.checkpoint

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID

enum class InventoryCheckpointReason { MANUAL, PERIODIC, JOIN, LOGOUT, DEATH, BEFORE_RESTORE }

/** Normal persistent player slots plus optional named ender/equipment items; never temporary cursor/crafting state. */
class InventoryCheckpoint @JvmOverloads constructor(
    val checkpointId: UUID,
    val playerId: UUID,
    val playerName: String,
    val capturedAt: Long,
    val minecraftVersion: String,
    val reason: InventoryCheckpointReason,
    val dimension: ResourceId,
    val position: BlockPosition,
    inventory: InventorySnapshot,
    val equipment: PlayerEquipmentSnapshot? = null
) {
    val inventory = InventorySnapshot(inventory.slots)

    val slotCount: Int get() = inventory.slots.size + (equipment?.slots?.size ?: 0)

    init {
        require(capturedAt >= 0)
        require(playerName.length in 1..64 && playerName.none(Char::isISOControl))
        require(minecraftVersion.length in 1..64 && minecraftVersion.none(Char::isISOControl))
        val owner = ItemSlotOwner.PlayerInventory(playerId)
        require(this.inventory.slots.size in setOf(41,43))
        require(this.inventory.slots.keys.all { it.owner == owner })
        require(this.inventory.slots.keys.map { it.index }.sorted() == (0 until this.inventory.slots.size).toList())
        require(this.inventory.slots.values.all { it.isEmpty || it.itemData != null && it.itemData.size > 0 })
        require(slotCount <= 256) { "Full player checkpoint exceeds slot budget" }
        require(this.inventory.slots.values.sumOf { (it.itemData?.size ?: 0).toLong() } +
            (equipment?.slots?.values?.sumOf { (it.itemData?.size ?: 0).toLong() } ?: 0L) <= MAX_COMPONENT_BYTES)
    }

    /** One owner binds all stored indices to the exact normal and named equipment topology. */
    val fullInventory: InventorySnapshot by lazy {
        val extra=equipment
        val owner=ItemSlotOwner.PlayerInventory(playerId,extra?.let { PlayerStorageLayout(inventory.slots.size,it.providers.keys,it.slots.keys) })
        val all=linkedMapOf<ItemSlotAddress,ItemStackSnapshot>()
        inventory.slots.entries.forEach { (address,item) -> all[ItemSlotAddress(owner,address.index)]=item }
        extra?.slots?.entries?.forEachIndexed { index,(address,item) ->
            check(address==owner.layout!!.extraSlots[index]);all[ItemSlotAddress(owner,inventory.slots.size+index)]=item
        }
        InventorySnapshot(all)
    }

    /** The snapshot is explicit creation/replacement authority, never a conserving transfer source. */
    fun replacementChanges(current: InventorySnapshot): List<ItemSlotChange> {
        require(current.slots.keys == fullInventory.slots.keys) { "Current inventory shape differs from checkpoint" }
        return fullInventory.slots.entries.sortedBy { it.key.index }.mapNotNull { (address, target) ->
            val observed = current.slots.getValue(address)
            if(observed == target) null else ItemSlotChange(address,target,observed)
        }
    }

    companion object { const val MAX_COMPONENT_BYTES = 1024L * 1024 }
}
