package com.bareminimumstudios.guardian.domain

import java.util.UUID

enum class BlockMovementPhase { PISTON, FALL_START, FALL_LAND }

data class BlockChangeSnapshot @JvmOverloads constructor(
    override val timestampEpochMillis: Long,
    override val actor: ActorIdentity,
    val dimension: ResourceId,
    val position: BlockPosition,
    val before: BlockStateSnapshot,
    val after: BlockStateSnapshot,
    val cause: ChangeCause,
    override val action: ActionType,
    val eventId: UUID = UUID.randomUUID(),
    val movementId: UUID? = null,
    val movementPhase: BlockMovementPhase? = null
) : LogEntry {
    init {
        require((movementId==null)==(movementPhase==null))
        require(movementPhase==null || before.blockId.toString()!="minecraft:moving_piston" && after.blockId.toString()!="minecraft:moving_piston")
        require(movementPhase==null || if(movementPhase==BlockMovementPhase.PISTON) cause==ChangeCause.PISTON else cause==ChangeCause.GRAVITY)
        require(
            action == ActionType.BLOCK_PLACE ||
                action == ActionType.BLOCK_BREAK ||
                action == ActionType.BLOCK_CHANGE
        ) { "BlockChangeSnapshot requires a block action, got $action" }
    }
}
