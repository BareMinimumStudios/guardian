package com.bareminimumstudios.guardian.storage.codec

import com.bareminimumstudios.guardian.domain.*
import java.io.*
import java.util.UUID

/** Bounded versioned encoding of an entire transaction's slot changes. */
object ContainerChangesCodec {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_ITEM_BYTES = 1024 * 1024
    const val MAX_SLOTS = 256
    fun encode(changes: List<ItemSlotChange>): ByteArray {
        require(changes.size in 1..MAX_SLOTS)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            val extended=changes.any { (it.address.owner as? ItemSlotOwner.PlayerInventory)?.layout!=null }
            val owners=if(extended)changes.map { it.address.owner }.distinct() else emptyList()
            output.writeInt(if(extended)0x47435434 else if(changes.any { it.address.owner is ItemSlotOwner.EntityContainer })0x47435433 else if (changes.any { it.address.owner is ItemSlotOwner.CraftingGrid }) 0x47435432 else 0x47435431); output.writeInt(changes.size)
            if(extended) {
                require(owners.size in 1..32);output.writeByte(owners.size)
                owners.forEach { SlotOwnerWire.write(output,it) }
            }
            changes.forEach { change ->
                if(extended)output.writeByte(owners.indexOf(change.address.owner)) else when (val owner = change.address.owner) {
                    is ItemSlotOwner.PlayerInventory -> { require(owner.layout==null) { "Extended player storage requires the equipment owner codec" }; output.writeByte(1); output.writeUTF(owner.playerId.toString()) }
                    is ItemSlotOwner.CraftingGrid -> { output.writeByte(4); output.writeUTF(owner.playerId.toString()); output.writeInt(owner.menuId) }
                    is ItemSlotOwner.Cursor -> { output.writeByte(2); output.writeUTF(owner.playerId.toString()) }
                    is ItemSlotOwner.EntityContainer -> {
                        output.writeByte(5);output.writeUTF(owner.dimension.toString());output.writeUTF(owner.entityUuid.toString());output.writeUTF(owner.entityType.toString())
                        output.writeInt(owner.position.x);output.writeInt(owner.position.y);output.writeInt(owner.position.z)
                    }
                    is ItemSlotOwner.BlockContainer -> {
                        output.writeByte(3); output.writeUTF(owner.dimension.toString())
                        output.writeInt(owner.position.x); output.writeInt(owner.position.y); output.writeInt(owner.position.z)
                    }
                }
                output.writeInt(change.address.index)
                writeItem(output, change.before); writeItem(output, change.after)
                require(bytes.size() <= MAX_BYTES) { "Container transaction exceeds encoding budget" }
            }
        }
        return bytes.toByteArray()
    }
    fun decode(bytes: ByteArray): List<ItemSlotChange> {
        require(bytes.size <= MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val format = input.readInt()
            require(format == 0x47435431 || format == 0x47435432 || format == 0x47435433 || format == 0x47435434) { "Unsupported container changes format" }
            val size = input.readInt(); require(size in 1..MAX_SLOTS)
            val owners=if(format==0x47435434) {
                val count=input.readUnsignedByte();require(count in 1..32)
                List(count) { SlotOwnerWire.read(input) }.also { require(it.distinct().size==it.size) }
            } else emptyList()
            val changes = List(size) {
                val owner = if(format==0x47435434) {
                    val index=input.readUnsignedByte();require(index in owners.indices);owners[index]
                } else when (input.readUnsignedByte()) {
                    1 -> ItemSlotOwner.PlayerInventory(UUID.fromString(input.readUTF()))
                    2 -> ItemSlotOwner.Cursor(UUID.fromString(input.readUTF()))
                    3 -> ItemSlotOwner.BlockContainer(ResourceId.parse(input.readUTF()), BlockPosition(input.readInt(), input.readInt(), input.readInt()))
                    4 -> { require(format == 0x47435432 || format == 0x47435433); ItemSlotOwner.CraftingGrid(UUID.fromString(input.readUTF()), input.readInt()) }
                    5 -> { require(format==0x47435433);ItemSlotOwner.EntityContainer(ResourceId.parse(input.readUTF()),UUID.fromString(input.readUTF()),ResourceId.parse(input.readUTF()),BlockPosition(input.readInt(),input.readInt(),input.readInt())) }
                    else -> throw IllegalArgumentException("Unknown slot owner")
                }
                ItemSlotChange(ItemSlotAddress(owner, input.readInt()), readItem(input), readItem(input))
            }
            require(input.available() == 0) { "Trailing transaction bytes" }
            require(changes.map { it.address }.toSet().size == changes.size) { "Duplicate logical slot" }
            changes
        }
    }
    private fun writeItem(out: DataOutputStream, value: ItemStackSnapshot) {
        out.writeInt(value.count)
        if (!value.isEmpty) {
            out.writeUTF(checkNotNull(value.itemId).toString())
            val bytes = checkNotNull(value.itemData).copyBytes()
            require(bytes.size in 1..MAX_ITEM_BYTES)
            out.writeInt(bytes.size); out.write(bytes)
        }
    }
    private fun readItem(input: DataInputStream): ItemStackSnapshot {
        val count = input.readInt(); require(count >= 0)
        if (count == 0) return ItemStackSnapshot.EMPTY
        val id = ResourceId.parse(input.readUTF())
        val length = input.readInt(); require(length in 1..MAX_ITEM_BYTES && length <= input.available())
        val bytes = ByteArray(length); input.readFully(bytes)
        return ItemStackSnapshot(id, count, BinaryPayload.of(bytes))
    }
}
