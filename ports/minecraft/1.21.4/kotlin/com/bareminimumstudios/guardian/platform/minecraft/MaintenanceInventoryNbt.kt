package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.ItemRollbackImages
import net.minecraft.SharedConstants
import net.minecraft.nbt.*

/** Registry-independent comparison of the exact persisted component patches; no items/entities are instantiated. */
internal object MaintenanceInventoryNbt {
    private val sizes=mapOf("minecraft:barrel" to 27,"minecraft:chest" to 27,"minecraft:hopper" to 5,
        "minecraft:dispenser" to 9,"minecraft:dropper" to 9,"minecraft:furnace" to 3,"minecraft:blast_furnace" to 3,"minecraft:smoker" to 3)
    fun repair(document: CompoundTag, owner: ItemSlotOwner, images: ItemRollbackImages) {
        require(document.getInt("DataVersion")==SharedConstants.getCurrentVersion().dataVersion.version && document.contains("DataVersion",Tag.TAG_INT.toInt())) { "Saved Minecraft data version differs" }
        val original=images.original.slots.filterKeys { it.owner==owner }.entries.sortedBy { it.key.index }
        val expected=images.expected.slots.filterKeys { it.owner==owner }.entries.sortedBy { it.key.index }
        require(original.isNotEmpty() && original.map { it.key.index }==original.indices.toList())
        val holder: CompoundTag
        val key: String
        when(owner) {
            is ItemSlotOwner.PlayerInventory -> {
                require(original.size==41 && document.hasUUID("UUID") && document.getUUID("UUID")==owner.playerId) { "Saved player identity differs" }
                holder=document;key="Inventory"
            }
            is ItemSlotOwner.BlockContainer -> {
                val p=owner.position
                require(document.getInt("xPos")== (p.x shr 4) && document.getInt("zPos")== (p.z shr 4) &&
                    document.contains("xPos",Tag.TAG_INT.toInt()) && document.contains("zPos",Tag.TAG_INT.toInt()))
                require(document.getString("Status") in setOf("minecraft:full","full")) { "Incomplete chunk cannot be repaired" }
                val entities=document.get("block_entities") as? ListTag ?: error("Missing block entities")
                require(entities.all { it is CompoundTag })
                val matches=entities.map { it as CompoundTag }.filter { it.getInt("x")==p.x && it.getInt("y")==p.y && it.getInt("z")==p.z &&
                    it.contains("x",Tag.TAG_INT.toInt()) && it.contains("y",Tag.TAG_INT.toInt()) && it.contains("z",Tag.TAG_INT.toInt()) }
                require(matches.size==1) { "Missing or duplicate saved container" }
                holder=matches.single();key="Items"
                val blockName=savedBlock(document,p)
                val type=holder.getString("id")
                require(blockName==type || type=="minecraft:chest" && blockName=="minecraft:trapped_chest") { "Saved block state no longer matches its container" }
                require(sizes[type]==original.size && !holder.contains("LootTable")) { "Unsupported or sealed saved inventory" }
            }
            else -> error("Temporary inventory owners cannot be repaired")
        }
        val raw=holder.get(key)
        require(raw==null && owner is ItemSlotOwner.BlockContainer || raw is ListTag) { "Missing or invalid inventory list" }
        val actual=MutableList<CompoundTag?>(original.size) { null }
        (raw as? ListTag)?.forEach { value ->
            require(value is CompoundTag && value.contains("Slot",Tag.TAG_BYTE.toInt()))
            val saved=value.getByte("Slot").toInt() and 255
            val index=if(owner is ItemSlotOwner.PlayerInventory) when(saved) { in 0..35 -> saved;in 100..103 -> saved-64;150 -> 40;else -> error("Unknown player slot") } else saved
            require(index in actual.indices && actual[index]==null) { "Duplicate or unsupported saved slot" }
            require(value.allKeys.all { it in setOf("Slot","id","count","components") }) { "Unknown item fields cannot be discarded" }
            require(value.contains("id",Tag.TAG_STRING.toInt()) && (!value.contains("count") || value.contains("count",Tag.TAG_INT.toInt())))
            val normalized=value.copy();normalized.remove("Slot")
            val count=if(value.contains("count"))value.getInt("count") else 1
            require(count in 1..99);normalized.putInt("count",count)
            normalize(normalized);actual[index]=normalized
        }
        for(i in actual.indices) require(actual[i]==item(original[i].value) || actual[i]==item(expected[i].value)) {
            "Conflicting inventory slot $i at $owner; no live file will be overwritten"
        }
        val result=ListTag()
        expected.forEachIndexed { i, value -> item(value.value)?.let { stack ->
            val saved=if(owner is ItemSlotOwner.PlayerInventory) when(i) { in 0..35 -> i;in 36..39 -> i+64;else -> 150 } else i
            stack.putByte("Slot",saved.toByte());result.add(stack)
        } }
        holder.put(key,result)
    }

    /** Native 1.21 section palettes use padded words, with X then Z then Y indexing. */
    private fun savedBlock(chunk: CompoundTag, pos: BlockPosition): String {
        val sections=chunk.get("sections") as? ListTag ?: error("Missing saved block-state sections")
        require(sections.size<=256 && sections.all { it is CompoundTag })
        val matches=sections.map { it as CompoundTag }.filter { it.contains("Y",Tag.TAG_BYTE.toInt()) && it.getByte("Y").toInt()==(pos.y shr 4) }
        require(matches.size==1) { "Missing or duplicate block-state section" }
        val states=matches.single().get("block_states") as? CompoundTag ?: error("Missing saved block-state palette")
        val palette=states.get("palette") as? ListTag ?: error("Missing saved palette")
        require(palette.size in 1..4096 && palette.all { it is CompoundTag })
        val selected=if(palette.size==1) {
            require(!states.contains("data"));0
        } else {
            val bits=maxOf(4,32-Integer.numberOfLeadingZeros(palette.size-1))
            val perWord=64/bits
            require(states.contains("data",Tag.TAG_LONG_ARRAY.toInt()))
            val data=states.getLongArray("data")
            require(data.size==(4096+perWord-1)/perWord)
            val index=((pos.y and 15) shl 8) or ((pos.z and 15) shl 4) or (pos.x and 15)
            ((data[index/perWord] ushr ((index%perWord)*bits)) and ((1L shl bits)-1)).toInt()
        }
        require(selected in 0 until palette.size)
        val state=palette.getCompound(selected)
        require(state.contains("Name",Tag.TAG_STRING.toInt()))
        return state.getString("Name")
    }

    private fun item(snapshot: ItemStackSnapshot): CompoundTag? {
        if(snapshot.isEmpty)return null
        require(snapshot.count in 1..99)
        val tag=MinecraftItemSnapshotter.decodeTag(checkNotNull(snapshot.itemData).copyBytes())
        require(tag.allKeys.all { it in setOf("id","components") } && tag.getString("id")==snapshot.itemId.toString()) { "Invalid recorded item payload" }
        require(tag.contains("id",Tag.TAG_STRING.toInt()))
        normalize(tag);tag.putInt("count",snapshot.count)
        return tag
    }
    private fun normalize(tag: CompoundTag) {
        if(tag.contains("components")) {
            val patch=tag.get("components") as? CompoundTag ?: error("Invalid item component map")
            if(patch.isEmpty)tag.remove("components")
        }
    }
}
