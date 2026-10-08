package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import net.minecraft.SharedConstants
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.*
import net.minecraft.resources.RegistryOps
import net.minecraft.world.item.ItemStack

/** Vanilla saved main/armor/offhand slots, matched by UUID; never constructs a player. */
object MinecraftSavedPlayerDecoder {
    fun decode(tag: CompoundTag,owner: ItemSlotOwner.PlayerInventory,wanted: Set<ItemSlotAddress>,registries: HolderLookup.Provider): InventorySnapshot {
        require(tag.contains("DataVersion",Tag.TAG_INT.toInt()) && tag.getInt("DataVersion")==SharedConstants.getCurrentVersion().dataVersion.version)
        require(tag.hasUUID("UUID") && tag.getUUID("UUID")==owner.playerId) { "Saved player UUID mismatch" }
        require(wanted.size in 1..41 && wanted.all { it.owner==owner && it.index in 0..40 })
        val raw=tag.get("Inventory") as? ListTag ?: error("Missing saved player inventory")
        require(raw.size<=41)
        val values=linkedMapOf<Int,ItemStackSnapshot>();val capture=MinecraftItemSnapshotter.CaptureBatch(registries)
        raw.forEach { entry ->
            require(entry is CompoundTag && entry.contains("Slot",Tag.TAG_BYTE.toInt()))
            val saved=entry.getByte("Slot").toInt() and 255
            val slot=when(saved) { in 0..35 -> saved;in 100..103 -> saved-100+36;150 -> 40;else -> error("Unknown saved player slot") }
            require(slot !in values) { "Duplicate saved slot" }
            val stack=ItemStack.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE,registries),entry).orThrow
            require(!stack.isEmpty)
            values[slot]=if(wanted.any { it.index==slot }) capture.capture(stack) else ItemStackSnapshot.EMPTY
        }
        val slots=wanted.associateWith { values[it.index] ?: ItemStackSnapshot.EMPTY }
        require(slots.values.sumOf { (it.itemData?.size ?: 0).toLong() }<=16L*1024*1024)
        return InventorySnapshot(slots)
    }
}
