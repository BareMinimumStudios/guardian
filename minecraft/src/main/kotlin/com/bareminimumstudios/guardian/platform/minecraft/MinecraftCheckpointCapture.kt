package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.checkpoint.*
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.MinecraftInventoryObservation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

object MinecraftCheckpointCapture {
    class Observed(val checkpoint: InventoryCheckpoint,val binding: MinecraftInventoryObservation)

    internal fun validateTarget(server: MinecraftServer,player: ServerPlayer,checkpoint: InventoryCheckpoint) {
        check(server.isSameThread)
        if(checkpoint.equipment==null)return
        require(player.uuid==checkpoint.playerId)
        val layout=checkNotNull((checkpoint.fullInventory.slots.keys.first().owner as ItemSlotOwner.PlayerInventory).layout)
        MinecraftPlayerEquipmentStorage.bind(player,layout).validateTarget(checkpoint.fullInventory)
    }

    @JvmOverloads
    fun observe(server:MinecraftServer,player:ServerPlayer,reason:InventoryCheckpointReason,
                time:Long=System.currentTimeMillis(),id:UUID=UUID.randomUUID(),includeEquipment:Boolean=true):Observed {
        check(server.isSameThread)
        if(!MinecraftInventoryCoordination.allowsPlayerMutation(player))
            throw CheckpointRestoreRefusedException("An inventory recovery operation is retaining items")
        val owner=ItemSlotOwner.PlayerInventory(player.uuid)
        val wanted=(0 until player.inventory.containerSize).map { ItemSlotAddress(owner,it) }.toSet()
        var detail="Player inventory is unavailable"
        val observed=MinecraftInventoryObservation.read(server,owner,wanted,InventoryCheckpoint.MAX_COMPONENT_BYTES) { detail=it }
            ?: throw CheckpointRestoreRefusedException(detail)
        observed.failureReason()?.let { throw CheckpointRestoreRefusedException(it) }
        val providers=if(includeEquipment)MinecraftEquipmentProviders(EquipmentModDiscovery.installed()) else null
        val equipment=providers?.observe(player)
        val position=player.blockPosition()
        val checkpoint=InventoryCheckpoint(id,player.uuid,player.gameProfile.name,time,GuardianMinecraftVersion.current,reason,
            MinecraftDimensionId.of(player.createCommandSourceStack().level),BlockPosition(position.x,position.y,position.z),observed.snapshot,equipment?.image)
        if(equipment==null)return Observed(checkpoint,observed)
        // Preflight native ownership support before a restore can persist claims.
        val storage=MinecraftPlayerEquipmentStorage.bind(player,checkNotNull((checkpoint.fullInventory.slots.keys.first().owner as ItemSlotOwner.PlayerInventory).layout))
        val binding=MinecraftInventoryObservation.combined(checkpoint.fullInventory) {
            observed.failureReason() ?: if(!checkNotNull(providers).matches(player,equipment) || !storage.isCurrent())
                "Equipment storage or provider layout changed during checking"
            else if(checkNotNull(providers).observe(player).image!=equipment.image) "Equipped item contents changed during checking" else null
        }
        binding.failureReason()?.let { throw CheckpointRestoreRefusedException(it) }
        return Observed(checkpoint,binding)
    }
}
