package com.bareminimumstudios.guardian.storage.codec

import com.bareminimumstudios.guardian.domain.*
import java.io.*

object ItemFrameSnapshotCodec {
    private const val MAX_ITEM_BYTES=1024*1024
    fun encode(value: ItemFrameSnapshot): ByteArray {
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(0x47465231);out.writeByte(value.rotation);out.writeByte(value.item.count)
            if(!value.item.isEmpty) {
                out.writeUTF(checkNotNull(value.item.itemId).toString())
                val data=checkNotNull(value.item.itemData).copyBytes();require(data.size in 1..MAX_ITEM_BYTES)
                out.writeInt(data.size);out.write(data)
            }
        }
        require(bytes.size()<=MAX_ITEM_BYTES+1024) { "Frame snapshot exceeds encoding budget" }
        return bytes.toByteArray()
    }
    fun decode(bytes: ByteArray): ItemFrameSnapshot {
        require(bytes.size in 6..MAX_ITEM_BYTES+1024)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt()==0x47465231) { "Unsupported item frame snapshot format" }
            val rotation=input.readUnsignedByte();val count=input.readUnsignedByte();require(count in 0..1)
            val item=if(count==0) ItemStackSnapshot.EMPTY else {
                val id=ResourceId.parse(input.readUTF());val size=input.readInt();require(size in 1..MAX_ITEM_BYTES && size<=input.available())
                val data=ByteArray(size);input.readFully(data);ItemStackSnapshot(id,1,BinaryPayload.of(data))
            }
            require(input.available()==0) { "Trailing frame snapshot bytes" };ItemFrameSnapshot(item,rotation)
        }
    }
}
