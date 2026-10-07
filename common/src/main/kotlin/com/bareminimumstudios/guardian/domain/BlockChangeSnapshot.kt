package com.bareminimumstudios.guardian.domain

import java.util.UUID

data class BlockChangeSnapshot(
    override val timestampEpochMillis: Long,
    override val actor: ActorIdentity,
    val dimension: ResourceId,
    val position: BlockPosition,
    val before: BlockStateSnapshot,
    val after: BlockStateSnapshot,
    val cause: ChangeCause,
    override val action: ActionType,
    val eventId: UUID = UUID.randomUUID()
) : LogEntry {
    init {
        require(
            action == ActionType.BLOCK_PLACE ||
                action == ActionType.BLOCK_BREAK ||
                action == ActionType.BLOCK_CHANGE
        ) { "BlockChangeSnapshot requires a block action, got $action" }
    }
}
