package com.bareminimumstudios.guardian.checkpoint

import com.bareminimumstudios.guardian.domain.*
import java.io.*
import java.security.MessageDigest
import java.util.UUID

/** Standalone full snapshot encoding; it grants no transfer or native mutation authority. */
object InventoryCheckpointCodec {
    private const val MAGIC = 0x47435031
    private const val EQUIPMENT_MAGIC = 0x47435032
    const val MAX_BYTES = 1024 * 1024 + 65536

    fun encode(checkpoint: InventoryCheckpoint): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(if(checkpoint.equipment==null)MAGIC else EQUIPMENT_MAGIC)
            for(id in listOf(checkpoint.checkpointId,checkpoint.playerId)) {
                out.writeLong(id.mostSignificantBits);out.writeLong(id.leastSignificantBits)
            }
            out.writeLong(checkpoint.capturedAt)
            out.writeUTF(checkpoint.playerName);out.writeUTF(checkpoint.minecraftVersion)
            out.writeUTF(checkpoint.reason.name);out.writeUTF(checkpoint.dimension.toString())
            out.writeInt(checkpoint.position.x);out.writeInt(checkpoint.position.y);out.writeInt(checkpoint.position.z)
            out.writeByte(checkpoint.inventory.slots.size)
            checkpoint.inventory.slots.entries.sortedBy { it.key.index }.forEach { (_,item) ->
                out.writeInt(item.count)
                if(!item.isEmpty) {
                    out.writeUTF(checkNotNull(item.itemId).toString())
                    val data=checkNotNull(item.itemData).copyBytes()
                    require(data.size in 1..1024*1024)
                    out.writeInt(data.size);out.write(data)
                }
                require(bytes.size() <= MAX_BYTES-32) { "Checkpoint encoding exceeds budget" }
            }
            checkpoint.equipment?.let { equipment ->
                out.writeInt(equipment.providers.size)
                equipment.providers.forEach { (id,version) -> out.writeUTF(id);out.writeUTF(version) }
                out.writeInt(equipment.slots.size)
                equipment.slots.forEach { (address,item) ->
                    out.writeUTF(address.provider);out.writeUTF(address.slot);out.writeInt(item.count)
                    if(!item.isEmpty) {
                        out.writeUTF(checkNotNull(item.itemId).toString())
                        val data=checkNotNull(item.itemData).copyBytes()
                        out.writeInt(data.size);out.write(data)
                    }
                    require(bytes.size()<=MAX_BYTES-32) { "Checkpoint encoding exceeds budget" }
                }
            }
        }
        val payload=bytes.toByteArray()
        return payload+MessageDigest.getInstance("SHA-256").digest(payload)
    }

    fun decode(bytes: ByteArray): InventoryCheckpoint {
        require(bytes.size in 96..MAX_BYTES) { "Checkpoint size is invalid" }
        val digest=MessageDigest.getInstance("SHA-256")
        digest.update(bytes,0,bytes.size-32)
        require(MessageDigest.isEqual(digest.digest(),bytes.copyOfRange(bytes.size-32,bytes.size))) { "Checkpoint checksum differs" }
        return DataInputStream(ByteArrayInputStream(bytes,0,bytes.size-32)).use { input ->
            val format=input.readInt()
            require(format==MAGIC || format==EQUIPMENT_MAGIC) { "Unsupported checkpoint encoding" }
            val id=UUID(input.readLong(),input.readLong());val player=UUID(input.readLong(),input.readLong())
            val time=input.readLong();val name=input.readUTF();val version=input.readUTF()
            val reason=InventoryCheckpointReason.valueOf(input.readUTF());val dimension=ResourceId.parse(input.readUTF())
            val position=BlockPosition(input.readInt(),input.readInt(),input.readInt())
            val size=input.readUnsignedByte();require(size==41 || size==43)
            val owner=ItemSlotOwner.PlayerInventory(player)
            val slots=linkedMapOf<ItemSlotAddress,ItemStackSnapshot>()
            repeat(size) { index ->
                val count=input.readInt();require(count>=0)
                val item=if(count==0)ItemStackSnapshot.EMPTY else {
                    val itemId=ResourceId.parse(input.readUTF());val length=input.readInt()
                    require(length in 1..1024*1024 && length<=input.available())
                    val data=ByteArray(length);input.readFully(data)
                    ItemStackSnapshot(itemId,count,BinaryPayload.of(data))
                }
                slots[ItemSlotAddress(owner,index)]=item
            }
            val equipment=if(format==EQUIPMENT_MAGIC) {
                val providers=linkedMapOf<String,String>()
                val providerCount=input.readInt();require(providerCount in 0..16)
                repeat(providerCount) {
                    val key=input.readUTF();require(key !in providers) { "Duplicate equipment provider" }
                    providers[key]=input.readUTF()
                }
                val extras=linkedMapOf<EquipmentSlotAddress,ItemStackSnapshot>()
                val count=input.readInt();require(count in 0..256-size)
                repeat(count) {
                    val address=EquipmentSlotAddress(input.readUTF(),input.readUTF())
                    require(address !in extras) { "Duplicate equipment slot" }
                    val amount=input.readInt();require(amount>=0)
                    val item=if(amount==0)ItemStackSnapshot.EMPTY else {
                        val itemId=ResourceId.parse(input.readUTF());val length=input.readInt()
                        require(length in 1..1024*1024 && length<=input.available())
                        val data=ByteArray(length);input.readFully(data)
                        ItemStackSnapshot(itemId,amount,BinaryPayload.of(data))
                    }
                    extras[address]=item
                }
                PlayerEquipmentSnapshot(providers,extras)
            } else null
            require(input.available()==0) { "Trailing checkpoint data" }
            InventoryCheckpoint(id,player,name,time,version,reason,dimension,position,InventorySnapshot(slots),equipment)
        }
    }
}
