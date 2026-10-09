package com.bareminimumstudios.guardian.storage.codec

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.*
import java.io.*
import java.util.UUID
import java.security.MessageDigest
import java.security.DigestOutputStream

/** Versioned complete images include unchanged/empty slots, unlike a transaction changes codec. */
object ItemRollbackImagesCodec {
    private const val MAGIC=0x47494931
    private const val MAX_ITEM_BYTES=1024*1024
    fun encode(images: ItemRollbackImages): ByteArray {
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC);out.write(fingerprint(images.record));out.writeInt(images.original.slots.size)
            images.original.slots.entries.sortedBy { it.key.toString() }.forEach { (address,item) ->
                when(val owner=address.owner) {
                    is ItemSlotOwner.PlayerInventory -> {out.writeByte(1);out.writeUTF(owner.playerId.toString())}
                    is ItemSlotOwner.BlockContainer -> {
                        out.writeByte(3);out.writeUTF(owner.dimension.toString());out.writeInt(owner.position.x);out.writeInt(owner.position.y);out.writeInt(owner.position.z)
                    }
                    else -> error("Temporary owners cannot be persisted for reconciliation")
                }
                out.writeInt(address.index);writeItem(out,item);writeItem(out,images.expected.slots.getValue(address))
                require(bytes.size().toLong()<=ItemRollbackImages.MAX_BYTES-32) { "Encoded owner images exceed budget" }
            }
        }
        val payload=bytes.toByteArray()
        return payload+MessageDigest.getInstance("SHA-256").digest(payload)
    }
    fun decode(record: ItemRollbackRecord, bytes: ByteArray): ItemRollbackImages {
        require(bytes.size.toLong()<=ItemRollbackImages.MAX_BYTES)
        require(bytes.size>=72) { "Truncated owner images" }
        val hash=MessageDigest.getInstance("SHA-256");hash.update(bytes,0,bytes.size-32)
        require(MessageDigest.isEqual(hash.digest(),bytes.copyOfRange(bytes.size-32,bytes.size))) { "Owner image checksum differs" }
        return DataInputStream(ByteArrayInputStream(bytes,0,bytes.size-32)).use { input ->
            require(input.readInt()==MAGIC) { "Unsupported owner images codec" }
            val bound=ByteArray(32);input.readFully(bound);require(MessageDigest.isEqual(bound,fingerprint(record))) { "Owner images belong to another journal plan" }
            val size=input.readInt();require(size in 1..2048)
            val original=linkedMapOf<ItemSlotAddress,ItemStackSnapshot>();val expected=linkedMapOf<ItemSlotAddress,ItemStackSnapshot>()
            repeat(size) {
                val owner=when(input.readUnsignedByte()) {
                    1 -> ItemSlotOwner.PlayerInventory(UUID.fromString(input.readUTF()))
                    3 -> ItemSlotOwner.BlockContainer(ResourceId.parse(input.readUTF()),BlockPosition(input.readInt(),input.readInt(),input.readInt()))
                    else -> throw IllegalArgumentException("Unsupported saved owner")
                }
                val address=ItemSlotAddress(owner,input.readInt());require(address !in original) { "Duplicate saved slot" }
                original[address]=readItem(input);expected[address]=readItem(input)
            }
            require(input.available()==0) { "Trailing saved image bytes" }
            ItemRollbackImages(record,InventorySnapshot(original)).also {
                require(it.expected.slots==expected) { "Persisted expected image differs from the journal plan" }
            }
        }
    }
    private fun fingerprint(record: ItemRollbackRecord): ByteArray {
        val hash=MessageDigest.getInstance("SHA-256")
        DataOutputStream(DigestOutputStream(OutputStream.nullOutputStream(),hash)).use { out ->
            out.writeUTF(record.operationId.toString());out.writeLong(record.createdAt);out.writeInt(record.entries.size)
            record.entries.forEach { entry ->
                out.writeUTF(entry.transactionId.toString());val payload=ContainerChangesCodec.encode(entry.changes)
                out.writeInt(payload.size);out.write(payload)
            }
        }
        return hash.digest()
    }
    private fun writeItem(out: DataOutputStream, item: ItemStackSnapshot) {
        out.writeInt(item.count)
        if(!item.isEmpty) {
            out.writeUTF(checkNotNull(item.itemId).toString())
            val data=checkNotNull(item.itemData).copyBytes();require(data.size in 1..MAX_ITEM_BYTES)
            out.writeInt(data.size);out.write(data)
        }
    }
    private fun readItem(input: DataInputStream): ItemStackSnapshot {
        val count=input.readInt();require(count>=0)
        if(count==0)return ItemStackSnapshot.EMPTY
        val id=ResourceId.parse(input.readUTF());val length=input.readInt()
        require(length in 1..MAX_ITEM_BYTES && length<=input.available())
        val data=ByteArray(length);input.readFully(data)
        return ItemStackSnapshot(id,count,BinaryPayload.of(data))
    }
}
