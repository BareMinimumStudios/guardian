package com.bareminimumstudios.guardian.platform.minecraft

import net.minecraft.core.UUIDUtil
import net.minecraft.world.entity.player.Inventory
import com.bareminimumstudios.guardian.domain.*
import net.minecraft.SharedConstants
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.*
import net.minecraft.resources.RegistryOps
import net.minecraft.world.item.ItemStack

/** Vanilla saved main/armor/offhand slots, matched by UUID; never constructs a player. */
object MinecraftSavedPlayerDecoder {
    internal fun currentDataVersion():Int=SharedConstants.getCurrentVersion().dataVersion().version()

    fun decode(tag: CompoundTag,owner: ItemSlotOwner.PlayerInventory,wanted: Set<ItemSlotAddress>,registries: HolderLookup.Provider): InventorySnapshot {
        require((tag.get("DataVersion")?.id == Tag.TAG_INT) && tag.getInt("DataVersion").orElseThrow()==currentDataVersion())
        require(tag.read("UUID",UUIDUtil.CODEC).isPresent && tag.read("UUID",UUIDUtil.CODEC).orElseThrow()==owner.playerId) { "Saved player UUID mismatch" }
        require(wanted.size in 1..43 && wanted.all { it.owner==owner && it.index in 0..42 })
        val raw=tag.get("Inventory") as? ListTag ?: error("Missing saved player inventory")
        require(raw.size<=36)
        val values=linkedMapOf<Int,ItemStackSnapshot>();val capture=MinecraftItemSnapshotter.CaptureBatch(registries)
        raw.forEach { entry ->
            require(entry is CompoundTag && (entry.get("Slot")?.id == Tag.TAG_BYTE))
            val saved=entry.getByte("Slot").orElseThrow().toInt() and 255
            require(saved in 0..35) { "Unsupported saved main-inventory slot" }
            val slot=saved
            require(slot !in values) { "Duplicate saved slot" }
            val stack=ItemStack.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE,registries),entry).orThrow
            require(!stack.isEmpty)
            values[slot]=if(wanted.any { it.index==slot }) capture.capture(stack) else ItemStackSnapshot.EMPTY
        }
        val equipment = tag.get("equipment")
        require(equipment == null || equipment is CompoundTag) { "Invalid saved equipment" }
        val equipmentSlots = (36..42).associateBy { requireNotNull(Inventory.EQUIPMENT_SLOT_MAPPING.get(it)).serializedName }
        equipment?.let { items ->
            require(items.keySet().all { it in equipmentSlots }) { "Unsupported saved equipment slot" }
            items.keySet().forEach { key ->
                val index=equipmentSlots.getValue(key)
                val stack=ItemStack.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE,registries),requireNotNull(items.get(key))).orThrow
                require(!stack.isEmpty)
                values[index]=if(wanted.any { it.index==index })capture.capture(stack) else ItemStackSnapshot.EMPTY
            }
        }
        val slots=wanted.associateWith { values[it.index] ?: ItemStackSnapshot.EMPTY }
        require(slots.values.sumOf { (it.itemData?.size ?: 0).toLong() }<=16L*1024*1024)
        return InventorySnapshot(slots)
    }
}
