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
            output.writeInt(0x47435431); output.writeInt(changes.size)
            changes.forEach { change ->
                when (val owner = change.address.owner) {
                    is ItemSlotOwner.PlayerInventory -> { output.writeByte(1); output.writeUTF(owner.playerId.toString()) }
                    is ItemSlotOwner.Cursor -> { output.writeByte(2); output.writeUTF(owner.playerId.toString()) }
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
            require(input.readInt() == 0x47435431) { "Unsupported container changes format" }
            val size = input.readInt(); require(size in 1..MAX_SLOTS)
            val changes = List(size) {
                val owner = when (input.readUnsignedByte()) {
                    1 -> ItemSlotOwner.PlayerInventory(UUID.fromString(input.readUTF()))
                    2 -> ItemSlotOwner.Cursor(UUID.fromString(input.readUTF()))
                    3 -> ItemSlotOwner.BlockContainer(ResourceId.parse(input.readUTF()), BlockPosition(input.readInt(), input.readInt(), input.readInt()))
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
