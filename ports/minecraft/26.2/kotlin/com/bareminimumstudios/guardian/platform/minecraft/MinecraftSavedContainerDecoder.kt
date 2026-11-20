package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import net.minecraft.SharedConstants
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.*
import net.minecraft.resources.RegistryOps
import net.minecraft.world.item.ItemStack

/** Strict saved physical vanilla inventories only. No block-entity construction or loot unpacking. */
object MinecraftSavedContainerDecoder {
    private val sizes=mapOf("minecraft:barrel" to 27,"minecraft:chest" to 27,"minecraft:trapped_chest" to 27,"minecraft:hopper" to 5,"minecraft:furnace" to 3,"minecraft:blast_furnace" to 3,"minecraft:smoker" to 3,"minecraft:dispenser" to 9,"minecraft:dropper" to 9,"minecraft:brewing_stand" to 5,"minecraft:shulker_box" to 27)
    fun decode(chunk: CompoundTag,owner: ItemSlotOwner.BlockContainer,wanted: Set<ItemSlotAddress>,registries: HolderLookup.Provider): InventorySnapshot {
        val p=owner.position
        require((chunk.get("DataVersion")?.id == Tag.TAG_INT) && chunk.getInt("DataVersion").orElseThrow()==SharedConstants.getCurrentVersion().dataVersion().version())
        require((chunk.get("xPos")?.id == Tag.TAG_INT) && (chunk.get("zPos")?.id == Tag.TAG_INT) && chunk.getInt("xPos").orElseThrow()==(p.x shr 4) && chunk.getInt("zPos").orElseThrow()==(p.z shr 4))
        require(chunk.getString("Status").orElseThrow() in setOf("minecraft:full","full"))
        val entities=chunk.get("block_entities") as? ListTag ?: error("Missing block entities")
        require(entities.all { it is CompoundTag })
        val matches=entities.map { it as CompoundTag }.filter { (it.get("x")?.id == Tag.TAG_INT) && (it.get("y")?.id == Tag.TAG_INT) && (it.get("z")?.id == Tag.TAG_INT) && it.getInt("x").orElseThrow()==p.x && it.getInt("y").orElseThrow()==p.y && it.getInt("z").orElseThrow()==p.z }
        require(matches.size==1) { "Missing or duplicate saved owner" }
        val entity=matches.single();val size=sizes[entity.getString("id").orElseThrow()] ?: error("Unsupported saved inventory layout")
        require(!entity.contains("LootTable")) { "Saved loot remains sealed" }
        require(wanted.size in 1..size && wanted.all { it.owner==owner && it.index in 0 until size })
        val raw=entity.get("Items");require(raw==null || raw is ListTag)
        val values=linkedMapOf<Int,ItemStackSnapshot>();val capture=MinecraftItemSnapshotter.CaptureBatch(registries)
        raw?.forEach { tag ->
            require(tag is CompoundTag && (tag.get("Slot")?.id == Tag.TAG_BYTE))
            val slot=tag.getByte("Slot").orElseThrow().toInt() and 255;require(slot in 0 until size && slot !in values)
            val stack=ItemStack.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE,registries),tag).orThrow
            require(!stack.isEmpty) { "Invalid saved item" }
            values[slot]=if(wanted.any { it.index==slot }) capture.capture(stack) else ItemStackSnapshot.EMPTY
        }
        val slots=wanted.associateWith { values[it.index] ?: ItemStackSnapshot.EMPTY }
        require(slots.values.sumOf { (it.itemData?.size ?: 0).toLong() }<=16L*1024*1024)
        return InventorySnapshot(slots)
    }
}
