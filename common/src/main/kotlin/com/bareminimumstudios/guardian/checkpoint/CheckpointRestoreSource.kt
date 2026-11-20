package com.bareminimumstudios.guardian.checkpoint

import com.bareminimumstudios.guardian.rollback.*
import java.util.UUID

/** A persisted checkpoint explicitly authorizes replacement, independently of conserving transfers. */
class CheckpointRestoreSource(val checkpoint: InventoryCheckpoint) {
    fun record(operationId: UUID, time: Long, before: InventoryCheckpoint): ItemRollbackRecord {
        require(operationId != checkpoint.checkpointId && before.checkpointId != checkpoint.checkpointId && operationId != before.checkpointId)
        require(time >= checkpoint.capturedAt && before.capturedAt == time) { "Checkpoint is ahead of server time; correct the clock before restoring" }
        require(before.playerId == checkpoint.playerId && before.reason == InventoryCheckpointReason.BEFORE_RESTORE)
        if(before.minecraftVersion != checkpoint.minecraftVersion)
            throw CheckpointRestoreRefusedException("Checkpoint belongs to Minecraft ${checkpoint.minecraftVersion}; current server is ${before.minecraftVersion}")
        val changes=checkpoint.replacementChanges(before.inventory)
        if(changes.isEmpty())throw CheckpointRestoreRefusedException("Player inventory already matches this checkpoint")
        return ItemRollbackRecord(operationId,time,ItemRollbackPhase.PREPARED,
            listOf(ItemRollbackEntry(checkpoint.checkpointId,changes)),ItemRecoveryPurpose.CHECKPOINT_RESTORE,checkpointOf=checkpoint.checkpointId)
    }
}

data class CheckpointRequester(val uuid: UUID?, val name: String) {
    init { require(name.length in 1..64 && name.none(Char::isISOControl)) }
}

data class CheckpointRestoreLink(val checkpoint: UUID, val before: UUID, val requester: CheckpointRequester)

class CheckpointRestoreRefusedException(message: String) : ItemRecoveryRefusedException(message)
