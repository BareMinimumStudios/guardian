package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.checkpoint.EquipmentModDiscovery
import com.bareminimumstudios.guardian.mixin.PlayerListSaveInvoker
import com.bareminimumstudios.guardian.rollback.ItemRollbackImages
import net.minecraft.core.HolderLookup
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.util.concurrent.CompletableFuture

/** Complete equipment readback from actual files, sharing the coordinator's bounded physical I/O drain. */
internal object MinecraftEquipmentSavedReadback {
    private fun platform()=if("neoforge" in EquipmentModDiscovery.installed())EquipmentSavePlatform.NEOFORGE else EquipmentSavePlatform.FABRIC

    fun read(server: MinecraftServer,storage: MinecraftPlayerEquipmentStorage,owner: ItemSlotOwner.PlayerInventory,
             baseline: InventorySnapshot,save: Boolean,reader: MinecraftSavedPlayerReader,
             current: ()->Boolean,validateRepair: (MinecraftSavedPlayerReader.EquipmentSavedDocuments)->Unit): CompletableFuture<InventorySnapshot> {
        check(server.isSameThread && storage.isCurrent() && current())
        val result=CompletableFuture<InventorySnapshot>()
        try {
            if(save)(server.playerList as PlayerListSaveInvoker).`guardian$savePlayer`(storage.player)
            val request=reader.readEquipment(server.getWorldPath(LevelResource.PLAYER_DATA_DIR),owner.playerId,
                "cosmeticarmorreworked" in checkNotNull(owner.layout).providers)
            check(current() && storage.isCurrent()) { "Equipment changed while saving" }
            request.whenComplete { documents,failure ->
                if(!result.isDone)try { server.execute {
                    if(!result.isDone)try {
                        check(failure==null && documents!=null && documents.isPresent) { "Complete saved equipment files are unavailable" }
                        check(current() && storage.isCurrent()) { "Live equipment changed during its saved-file read" }
                        val saved=verify(documents.get(),owner,baseline,server.registryAccess())
                        validateRepair(documents.get())
                        check(current() && storage.isCurrent()) { "Equipment changed during save verification" }
                        result.complete(saved)
                    } catch(error: Exception) { result.completeExceptionally(error) }
                } } catch(error: Exception) { result.completeExceptionally(error) }
            }
        } catch(error: Exception) { result.completeExceptionally(error) }
        return result
    }

    private fun extras(owner: ItemSlotOwner.PlayerInventory,image: InventorySnapshot): Map<EquipmentSlotAddress,ItemStackSnapshot> {
        val layout=checkNotNull(owner.layout)
        require(image.slots.keys.filter { it.owner==owner }.map { it.index }.toSet()==(0 until layout.slotCount).toSet())
        return layout.extraSlots.withIndex().associate { (index,address) -> address to image.slots.getValue(ItemSlotAddress(owner,layout.normalSlots+index)) }
    }

    fun verify(documents: MinecraftSavedPlayerReader.EquipmentSavedDocuments,owner: ItemSlotOwner.PlayerInventory,
               expected: InventorySnapshot,registries: HolderLookup.Provider): InventorySnapshot {
        val layout=checkNotNull(owner.layout)
        val normal=expected.slots.keys.filter { it.owner==owner && it.index<layout.normalSlots }.toSet()
        val decoded=MinecraftSavedPlayerDecoder.decode(documents.player,owner,normal,registries)
        require(decoded.slots==expected.slots.filterKeys { it.owner==owner && it.index<layout.normalSlots }) { "Saved normal inventory differs from its complete image" }
        MinecraftEquipmentSavedNbt.verify(documents.player,documents.cosmeticArmor,platform(),layout,extras(owner,expected))
        // Every additional item byte/count was compared by the strict opaque-NBT verifier.
        return InventorySnapshot(decoded.slots+expected.slots.filterKeys { it.owner==owner && it.index>=layout.normalSlots })
    }

    fun proveRepair(documents: MinecraftSavedPlayerReader.EquipmentSavedDocuments,owner: ItemSlotOwner.PlayerInventory,
                    images: ItemRollbackImages,registries: HolderLookup.Provider) {
        val repaired=MinecraftEquipmentSavedNbt.repair(documents.player,documents.cosmeticArmor,platform(),checkNotNull(owner.layout),
            extras(owner,images.original),extras(owner,images.expected))
        MaintenanceInventoryNbt.repairEquipmentNormalPart(repaired.player,owner,images)
        verify(MinecraftSavedPlayerReader.EquipmentSavedDocuments(repaired.player,repaired.cosmeticArmor),owner,images.expected,registries)
    }
}
