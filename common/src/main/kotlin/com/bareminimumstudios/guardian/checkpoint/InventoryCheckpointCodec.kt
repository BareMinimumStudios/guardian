package com.bareminimumstudios.guardian.checkpoint

import com.bareminimumstudios.guardian.domain.*
import java.io.*
import java.security.MessageDigest
import java.util.UUID

/** Standalone full snapshot encoding; it grants no transfer or native mutation authority. */
object InventoryCheckpointCodec {
    private const val MAGIC = 0x47435031
    const val MAX_BYTES = 1024 * 1024 + 16384

    fun encode(checkpoint: InventoryCheckpoint): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
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
            require(input.readInt()==MAGIC) { "Unsupported checkpoint encoding" }
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
            require(input.available()==0) { "Trailing checkpoint data" }
            InventoryCheckpoint(id,player,name,time,version,reason,dimension,position,InventorySnapshot(slots))
        }
    }
}
