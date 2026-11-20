package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.checkpoint.*
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.MinecraftInventoryObservation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

object MinecraftCheckpointCapture {
    class Observed(val checkpoint: InventoryCheckpoint,val binding: MinecraftInventoryObservation)

    fun observe(server:MinecraftServer,player:ServerPlayer,reason:InventoryCheckpointReason,
                time:Long=System.currentTimeMillis(),id:UUID=UUID.randomUUID()):Observed {
        check(server.isSameThread)
        if(!MinecraftInventoryCoordination.allowsPlayerMutation(player))
            throw CheckpointRestoreRefusedException("An inventory recovery operation is retaining items")
        val owner=ItemSlotOwner.PlayerInventory(player.uuid)
        val wanted=(0 until player.inventory.containerSize).map { ItemSlotAddress(owner,it) }.toSet()
        var detail="Player inventory is unavailable"
        val observed=MinecraftInventoryObservation.read(server,owner,wanted,InventoryCheckpoint.MAX_COMPONENT_BYTES) { detail=it }
            ?: throw CheckpointRestoreRefusedException(detail)
        observed.failureReason()?.let { throw CheckpointRestoreRefusedException(it) }
        val position=player.blockPosition()
        val checkpoint=InventoryCheckpoint(id,player.uuid,player.gameProfile.name,time,GuardianMinecraftVersion.current,reason,
            MinecraftDimensionId.of(player.createCommandSourceStack().level),BlockPosition(position.x,position.y,position.z),observed.snapshot)
        return Observed(checkpoint,observed)
    }
}
