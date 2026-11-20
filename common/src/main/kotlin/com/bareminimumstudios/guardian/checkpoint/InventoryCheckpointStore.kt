package com.bareminimumstudios.guardian.checkpoint

import java.util.UUID

data class InventoryCheckpointHeader(
    val checkpointId: UUID,
    val playerId: UUID,
    val playerName: String,
    val capturedAt: Long,
    val minecraftVersion: String,
    val reason: InventoryCheckpointReason,
    val slotCount: Int
)

data class InventoryCheckpointLimits(
    val perPlayer: Int = 128,
    val total: Int = 10000,
    val bytes: Long = 512L * 1024 * 1024
) {
    init { require(perPlayer in 1..1000 && total in perPlayer..100000 && bytes in 2L*1024*1024..16L*1024*1024*1024) }
}

/** Background-only immutable checkpoint storage; this surface never mutates player inventories. */
interface InventoryCheckpointStore {
    fun saveInventoryCheckpoint(checkpoint: InventoryCheckpoint, limits: InventoryCheckpointLimits): InventoryCheckpointHeader
    fun inventoryCheckpoint(id: UUID): InventoryCheckpoint?
    fun inventoryCheckpoints(playerId: UUID? = null, limit: Int = 10, offset: Int = 0, after: Long? = null): List<InventoryCheckpointHeader>
    fun inventoryCheckpointPlayers(name: String): List<UUID>
}

class InventoryCheckpointCapacityException(message: String) : IllegalStateException(message)
