package com.bareminimumstudios.guardian.storage.codec

import com.bareminimumstudios.guardian.domain.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/** Extended owner tables bind logical indices to recorded named equipment slots. */
internal object SlotOwnerWire {
    fun write(out:DataOutputStream,owner:ItemSlotOwner) {
        when(owner) {
            is ItemSlotOwner.PlayerInventory -> {
                val layout=owner.layout
                out.writeByte(if(layout==null)1 else 6);out.writeUTF(owner.playerId.toString())
                if(layout!=null) {
                    out.writeByte(layout.normalSlots);out.writeByte(layout.providers.size)
                    layout.providers.forEach(out::writeUTF)
                    out.writeShort(layout.extraSlots.size)
                    layout.extraSlots.forEach { out.writeUTF(it.provider);out.writeUTF(it.slot) }
                }
            }
            is ItemSlotOwner.Cursor -> { out.writeByte(2);out.writeUTF(owner.playerId.toString()) }
            is ItemSlotOwner.BlockContainer -> {
                out.writeByte(3);out.writeUTF(owner.dimension.toString());position(out,owner.position)
            }
            is ItemSlotOwner.CraftingGrid -> { out.writeByte(4);out.writeUTF(owner.playerId.toString());out.writeInt(owner.menuId) }
            is ItemSlotOwner.EntityContainer -> {
                out.writeByte(5);out.writeUTF(owner.dimension.toString());out.writeUTF(owner.entityUuid.toString());out.writeUTF(owner.entityType.toString());position(out,owner.position)
            }
        }
    }
    fun read(input:DataInputStream):ItemSlotOwner = when(input.readUnsignedByte()) {
        1 -> ItemSlotOwner.PlayerInventory(UUID.fromString(input.readUTF()))
        2 -> ItemSlotOwner.Cursor(UUID.fromString(input.readUTF()))
        3 -> ItemSlotOwner.BlockContainer(ResourceId.parse(input.readUTF()),position(input))
        4 -> ItemSlotOwner.CraftingGrid(UUID.fromString(input.readUTF()),input.readInt())
        5 -> ItemSlotOwner.EntityContainer(ResourceId.parse(input.readUTF()),UUID.fromString(input.readUTF()),ResourceId.parse(input.readUTF()),position(input))
        6 -> {
            val player=UUID.fromString(input.readUTF());val normal=input.readUnsignedByte()
            require(normal==41 || normal==43)
            val count=input.readUnsignedByte();require(count<=16)
            val providers=List(count) { input.readUTF() }
            require(providers==providers.distinct().sorted()) { "Noncanonical equipment providers" }
            val slots=input.readUnsignedShort();require(slots<=256-normal)
            val names=List(slots) { EquipmentSlotAddress(input.readUTF(),input.readUTF()) }
            val layout=PlayerStorageLayout(normal,providers.toSet(),names)
            require(names==layout.extraSlots) { "Noncanonical equipment topology" }
            ItemSlotOwner.PlayerInventory(player,layout)
        }
        else -> error("Unknown slot owner")
    }
    private fun position(out:DataOutputStream,pos:BlockPosition) { out.writeInt(pos.x);out.writeInt(pos.y);out.writeInt(pos.z) }
    private fun position(input:DataInputStream)=BlockPosition(input.readInt(),input.readInt(),input.readInt())
}
