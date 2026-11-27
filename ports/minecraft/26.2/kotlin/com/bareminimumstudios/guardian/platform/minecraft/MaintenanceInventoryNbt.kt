package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.rollback.ItemRecoveryPurpose
import net.minecraft.core.UUIDUtil
import net.minecraft.world.entity.player.Inventory
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.ItemRollbackImages
import net.minecraft.SharedConstants
import net.minecraft.nbt.*

/** Registry-independent comparison of the exact persisted component patches; no items/entities are instantiated. */
internal object MaintenanceInventoryNbt {
    private val sizes=mapOf("minecraft:barrel" to 27,"minecraft:chest" to 27,"minecraft:hopper" to 5,
        "minecraft:dispenser" to 9,"minecraft:dropper" to 9,"minecraft:furnace" to 3,"minecraft:blast_furnace" to 3,"minecraft:smoker" to 3)
    fun repair(document: CompoundTag, owner: ItemSlotOwner, images: ItemRollbackImages) {
        require(owner !is ItemSlotOwner.PlayerInventory || owner.layout==null) { "Equipment owners require their combined saved-file repair" }
        repairNormal(document,owner,images)
    }

    /** Used only after the equipment planner validates every extra slot in private file copies. */
    fun repairEquipmentNormalPart(document: CompoundTag, owner: ItemSlotOwner.PlayerInventory, images: ItemRollbackImages) {
        require(owner.layout!=null && images.record.purpose==ItemRecoveryPurpose.CHECKPOINT_RESTORE)
        repairNormal(document,owner,images)
    }

    private fun repairNormal(document: CompoundTag, owner: ItemSlotOwner, images: ItemRollbackImages) {
        require(document.getInt("DataVersion").orElseThrow()==SharedConstants.getCurrentVersion().dataVersion().version() && (document.get("DataVersion")?.id == Tag.TAG_INT)) { "Saved Minecraft data version differs" }
        if(owner is ItemSlotOwner.PlayerInventory) { repairPlayer(document,owner,images);return }
        val original=images.original.slots.filterKeys { address -> address.owner==owner && ((owner as? ItemSlotOwner.PlayerInventory)?.layout?.normalSlots?.let { address.index<it } ?: true) }.entries.sortedBy { it.key.index }
        val expected=images.expected.slots.filterKeys { address -> address.owner==owner && ((owner as? ItemSlotOwner.PlayerInventory)?.layout?.normalSlots?.let { address.index<it } ?: true) }.entries.sortedBy { it.key.index }
        require(original.isNotEmpty() && original.map { it.key.index }==original.indices.toList())
        val holder: CompoundTag
        val key: String
        when(owner) {
            is ItemSlotOwner.PlayerInventory -> {
                require(original.size==41 && document.read("UUID",UUIDUtil.CODEC).isPresent && document.read("UUID",UUIDUtil.CODEC).orElseThrow()==owner.playerId) { "Saved player identity differs" }
                holder=document;key="Inventory"
            }
            is ItemSlotOwner.BlockContainer -> {
                val p=owner.position
                require(document.getInt("xPos").orElseThrow()== (p.x shr 4) && document.getInt("zPos").orElseThrow()== (p.z shr 4) &&
                    (document.get("xPos")?.id == Tag.TAG_INT) && (document.get("zPos")?.id == Tag.TAG_INT))
                require(document.getString("Status").orElseThrow() in setOf("minecraft:full","full")) { "Incomplete chunk cannot be repaired" }
                val entities=document.get("block_entities") as? ListTag ?: error("Missing block entities")
                require(entities.all { it is CompoundTag })
                val matches=entities.map { it as CompoundTag }.filter { it.getInt("x").orElseThrow()==p.x && it.getInt("y").orElseThrow()==p.y && it.getInt("z").orElseThrow()==p.z &&
                    (it.get("x")?.id == Tag.TAG_INT) && (it.get("y")?.id == Tag.TAG_INT) && (it.get("z")?.id == Tag.TAG_INT) }
                require(matches.size==1) { "Missing or duplicate saved container" }
                holder=matches.single();key="Items"
                val blockName=savedBlock(document,p)
                val type=holder.getString("id").orElseThrow()
                require(blockName==type || type=="minecraft:chest" && blockName=="minecraft:trapped_chest") { "Saved block state no longer matches its container" }
                require(sizes[type]==original.size && !holder.contains("LootTable")) { "Unsupported or sealed saved inventory" }
            }
            else -> error("Temporary inventory owners cannot be repaired")
        }
        val raw=holder.get(key)
        require(raw==null && owner is ItemSlotOwner.BlockContainer || raw is ListTag) { "Missing or invalid inventory list" }
        val actual=MutableList<CompoundTag?>(original.size) { null }
        raw?.forEach { value ->
            require(value is CompoundTag && (value.get("Slot")?.id == Tag.TAG_BYTE))
            val saved=value.getByte("Slot").orElseThrow().toInt() and 255
            val index=if(owner is ItemSlotOwner.PlayerInventory) when(saved) { in 0..35 -> saved;in 100..103 -> saved-64;150 -> 40;else -> error("Unknown player slot") } else saved
            require(index in actual.indices && actual[index]==null) { "Duplicate or unsupported saved slot" }
            require(value.keySet().all { it in setOf("Slot","id","count","components") }) { "Unknown item fields cannot be discarded" }
            require((value.get("id")?.id == Tag.TAG_STRING) && (!value.contains("count") || (value.get("count")?.id == Tag.TAG_INT)))
            val normalized=value.copy();normalized.remove("Slot")
            val count=if(value.contains("count"))value.getInt("count").orElseThrow() else 1
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

    /** 26.2 saves main inventory and equipment independently; neither side may be omitted from a repair. */
    private fun repairPlayer(document: CompoundTag, owner: ItemSlotOwner.PlayerInventory, images: ItemRollbackImages) {
        require(document.read("UUID",UUIDUtil.CODEC).orElseThrow()==owner.playerId) { "Saved player identity differs" }
        val original=images.original.slots.filterKeys { address -> address.owner==owner && (owner.layout?.normalSlots?.let { address.index<it } ?: true) }.entries.sortedBy { it.key.index }
        val expected=images.expected.slots.filterKeys { address -> address.owner==owner && (owner.layout?.normalSlots?.let { address.index<it } ?: true) }.entries.sortedBy { it.key.index }
        require(original.size==43 && expected.size==43 && original.map { it.key.index }==(0..42).toList() && expected.map { it.key.index }==(0..42).toList())
        val actual=MutableList<CompoundTag?>(43) { null }
        fun normalized(raw: CompoundTag, slotted: Boolean): CompoundTag {
            val allowed=if(slotted)setOf("Slot","id","count","components") else setOf("id","count","components")
            require(raw.keySet().all { it in allowed } && raw.get("id") is StringTag) { "Unsupported saved item fields" }
            require(!raw.contains("count") || raw.get("count") is IntTag)
            return raw.copy().also { tag ->
                tag.remove("Slot")
                val count=if(tag.contains("count"))tag.getInt("count").orElseThrow() else 1
                require(count in 1..99);tag.putInt("count",count);normalize(tag)
            }
        }
        val inventory=document.get("Inventory") as? ListTag ?: error("Missing saved player inventory")
        require(inventory.size<=36)
        inventory.forEach { entry ->
            require(entry is CompoundTag && entry.get("Slot") is ByteTag)
            val slot=entry.getByte("Slot").orElseThrow().toInt() and 255
            require(slot in 0..35 && actual[slot]==null) { "Duplicate or unsupported saved slot" }
            actual[slot]=normalized(entry,true)
        }
        val names=(36..42).associateBy { requireNotNull(Inventory.EQUIPMENT_SLOT_MAPPING.get(it)).serializedName }
        val equipment=document.get("equipment")
        require(equipment==null || equipment is CompoundTag)
        equipment?.let { items ->
            require(items.keySet().all { it in names }) { "Unsupported saved equipment slot" }
            items.keySet().forEach { key -> actual[names.getValue(key)]=normalized(items.get(key) as? CompoundTag ?: error("Invalid equipment item"),false) }
        }
        for(i in actual.indices)require(actual[i]==item(original[i].value) || actual[i]==item(expected[i].value)) { "Conflicting saved player slot $i; no live file will be overwritten" }
        val result=ListTag();val resultEquipment=CompoundTag()
        expected.forEachIndexed { index,entry -> item(entry.value)?.let { stack ->
            if(index<36) { stack.putByte("Slot",index.toByte());result.add(stack) }
            else resultEquipment.put(requireNotNull(Inventory.EQUIPMENT_SLOT_MAPPING.get(index)).serializedName,stack)
        } }
        document.put("Inventory",result)
        if(resultEquipment.isEmpty)document.remove("equipment") else document.put("equipment",resultEquipment)
    }

    /** Native 1.21 section palettes use padded words, with X then Z then Y indexing. */
    private fun savedBlock(chunk: CompoundTag, pos: BlockPosition): String {
        val sections=chunk.get("sections") as? ListTag ?: error("Missing saved block-state sections")
        require(sections.size<=256 && sections.all { it is CompoundTag })
        val matches=sections.map { it as CompoundTag }.filter { (it.get("Y")?.id == Tag.TAG_BYTE) && it.getByte("Y").orElseThrow().toInt()==(pos.y shr 4) }
        require(matches.size==1) { "Missing or duplicate block-state section" }
        val states=matches.single().get("block_states") as? CompoundTag ?: error("Missing saved block-state palette")
        val palette=states.get("palette") as? ListTag ?: error("Missing saved palette")
        require(palette.size in 1..4096 && palette.all { it is CompoundTag })
        val selected=if(palette.size==1) {
            require(!states.contains("data"));0
        } else {
            val bits=maxOf(4,32-Integer.numberOfLeadingZeros(palette.size-1))
            val perWord=64/bits
            require((states.get("data")?.id == Tag.TAG_LONG_ARRAY))
            val data=states.getLongArray("data").orElseThrow()
            require(data.size==(4096+perWord-1)/perWord)
            val index=((pos.y and 15) shl 8) or ((pos.z and 15) shl 4) or (pos.x and 15)
            ((data[index/perWord] ushr ((index%perWord)*bits)) and ((1L shl bits)-1)).toInt()
        }
        require(selected in 0 until palette.size)
        val state=palette.getCompound(selected).orElseThrow()
        require((state.get("Name")?.id == Tag.TAG_STRING))
        return state.getString("Name").orElseThrow()
    }

    private fun item(snapshot: ItemStackSnapshot): CompoundTag? {
        if(snapshot.isEmpty)return null
        require(snapshot.count in 1..99)
        val tag=MinecraftItemSnapshotter.decodeTag(checkNotNull(snapshot.itemData).copyBytes())
        require(tag.keySet().all { it in setOf("id","components") } && tag.getString("id").orElseThrow()==snapshot.itemId.toString()) { "Invalid recorded item payload" }
        require((tag.get("id")?.id == Tag.TAG_STRING))
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
