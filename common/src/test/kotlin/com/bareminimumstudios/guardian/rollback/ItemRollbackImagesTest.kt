package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.codec.ItemRollbackImagesCodec
import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.*

class ItemRollbackImagesTest {
    private val a=ItemSlotAddress(ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"),BlockPosition(0,64,0)),0)
    private val b=ItemSlotAddress(ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"),BlockPosition(1,64,0)),0)
    private val item=ItemStackSnapshot(ResourceId.parse("minecraft:coal"),2,BinaryPayload.of(byteArrayOf(1,2)))
    private val spare=ItemStackSnapshot(ResourceId.parse("minecraft:diamond_pickaxe"),1,BinaryPayload.of(byteArrayOf(11,17)))
    private fun record(left: ItemSlotAddress=a,right: ItemSlotAddress=b)=ItemRollbackRecord(UUID.randomUUID(),100,ItemRollbackPhase.PREPARED,listOf(ItemRollbackEntry(UUID.randomUUID(),listOf(
        ItemSlotChange(left,item,ItemStackSnapshot.EMPTY),ItemSlotChange(right,ItemStackSnapshot.EMPTY,item)))))
    private fun original()=InventorySnapshot(linkedMapOf(a to ItemStackSnapshot.EMPTY,a.copy(index=1) to spare,a.copy(index=2) to ItemStackSnapshot.EMPTY,b to item,b.copy(index=1) to ItemStackSnapshot.EMPTY,b.copy(index=2) to spare))
    private fun image()=ItemRollbackImages(record(),original())
    private fun rehash(body: ByteArray)=body+MessageDigest.getInstance("SHA-256").digest(body)
    @Test fun fullImagesPreserveUnchangedComponentsAndEmptySlots() {
        val image=image();assertEquals(6,image.expected.slots.size);assertEquals(item,image.expected.slots[a]);assertEquals(ItemStackSnapshot.EMPTY,image.expected.slots[b])
        assertEquals(spare,image.expected.slots[a.copy(index=1)]);assertEquals(spare,image.expected.slots[b.copy(index=2)])
    }
    @Test fun imagesCopyMutableInputAndExposeImmutableMaps() {
        val map=original().slots.toMutableMap();val image=ItemRollbackImages(record(),InventorySnapshot(map));map.clear()
        assertEquals(6,image.original.slots.size)
        assertFailsWith<UnsupportedOperationException>{(image.expected.slots as MutableMap).clear()}
    }
    @Test fun wrongObservedChangedSlotRefusesBeforePersistence() {
        val bad=original().slots.toMutableMap();bad[b]=ItemStackSnapshot.EMPTY
        assertFailsWith<IllegalArgumentException>{ItemRollbackImages(record(),InventorySnapshot(bad))}
    }
    @Test fun missingMiddleOrChangedSlotRefusesCompleteImage() {
        for(address in listOf(a,b,a.copy(index=1))) assertFailsWith<IllegalArgumentException>{ItemRollbackImages(record(),InventorySnapshot(original().slots-address))}
    }
    @Test fun extraOwnerOrTemporarySlotIsRefused() {
        val foreign=ItemSlotAddress(ItemSlotOwner.Cursor(UUID.randomUUID()),0)
        assertFailsWith<IllegalArgumentException>{ItemRollbackImages(record(),InventorySnapshot(original().slots+(foreign to ItemStackSnapshot.EMPTY)))}
    }
    @Test fun playerRequiresAllMainArmorAndOffhandSlots() {
        val p=ItemSlotOwner.PlayerInventory(UUID.randomUUID());val q=ItemSlotOwner.PlayerInventory(UUID.randomUUID())
        val left=ItemSlotAddress(p,0);val right=ItemSlotAddress(q,0);val record=record(left,right)
        val map=(listOf(p,q).flatMap { owner->(0..40).map { ItemSlotAddress(owner,it) to ItemStackSnapshot.EMPTY } }).toMap().toMutableMap();map[right]=item
        map[ItemSlotAddress(p,40)]=spare;map[ItemSlotAddress(q,36)]=spare
        val image=ItemRollbackImages(record,InventorySnapshot(map));assertEquals(82,image.expected.slots.size)
        val decoded=ItemRollbackImagesCodec.decode(record,ItemRollbackImagesCodec.encode(image))
        assertEquals(image.expected.slots,decoded.expected.slots);assertEquals(spare,decoded.expected.slots[ItemSlotAddress(p,40)]);assertEquals(spare,decoded.expected.slots[ItemSlotAddress(q,36)])
        map.remove(ItemSlotAddress(p,40));assertFailsWith<IllegalArgumentException>{ItemRollbackImages(record,InventorySnapshot(map))}
    }
    @Test fun maximumSlotBudgetRoundTripsAndExtraSlotIsRefused() {
        val owners=(0..7).map { ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"),BlockPosition(it,64,0)) }
        val changes=owners.chunked(2).flatMap { (left,right)->listOf(ItemSlotChange(ItemSlotAddress(left,0),item,ItemStackSnapshot.EMPTY),ItemSlotChange(ItemSlotAddress(right,0),ItemStackSnapshot.EMPTY,item)) }
        val record=ItemRollbackRecord(UUID.randomUUID(),1,ItemRollbackPhase.PREPARED,listOf(ItemRollbackEntry(UUID.randomUUID(),changes)))
        val map=owners.flatMap { owner->(0..255).map { ItemSlotAddress(owner,it) to ItemStackSnapshot.EMPTY } }.toMap().toMutableMap()
        changes.forEach {map[it.address]=it.after};val image=ItemRollbackImages(record,InventorySnapshot(map))
        assertEquals(2048,ItemRollbackImagesCodec.decode(record,ItemRollbackImagesCodec.encode(image)).expected.slots.size)
        map[ItemSlotAddress(owners[0],256)]=ItemStackSnapshot.EMPTY;assertFailsWith<IllegalArgumentException>{ItemRollbackImages(record,InventorySnapshot(map))}
    }
    @Test fun codecRoundTripsAllImagesAcrossJournalPhaseChanges() {
        val image=image();val completed=ItemRollbackRecord(image.record.operationId,image.record.createdAt,ItemRollbackPhase.COMPLETED,image.record.entries)
        val decoded=ItemRollbackImagesCodec.decode(completed,ItemRollbackImagesCodec.encode(image))
        assertEquals(image.original.slots,decoded.original.slots);assertEquals(image.expected.slots,decoded.expected.slots)
    }
    @Test fun codecRejectsAnotherOperationTimestampOrSourceManifest() {
        val image=image();val bytes=ItemRollbackImagesCodec.encode(image);val record=image.record
        for(other in listOf(ItemRollbackRecord(UUID.randomUUID(),record.createdAt,record.phase,record.entries),ItemRollbackRecord(record.operationId,101,record.phase,record.entries),
            ItemRollbackRecord(record.operationId,record.createdAt,record.phase,listOf(ItemRollbackEntry(UUID.randomUUID(),record.entries.single().changes)))))
            assertFailsWith<IllegalArgumentException>{ItemRollbackImagesCodec.decode(other,bytes)}
    }
    @Test fun corruptionTruncationAndTrailingBytesAreRefused() {
        val image=image();val bytes=ItemRollbackImagesCodec.encode(image);val corrupt=bytes.copyOf();corrupt[corrupt.size/2]=(corrupt[corrupt.size/2].toInt() xor 1).toByte()
        for(bad in listOf(corrupt,bytes.copyOf(8),bytes.copyOf(bytes.size-1),bytes+byteArrayOf(0)))assertFailsWith<IllegalArgumentException>{ItemRollbackImagesCodec.decode(image.record,bad)}
    }
    @Test fun checksumValidUnknownVersionAndNegativeItemCountAreRefused() {
        val image=image();val bytes=ItemRollbackImagesCodec.encode(image);val body=bytes.copyOf(bytes.size-32)
        val version=body.copyOf();version[3]=(version[3].toInt() xor 1).toByte()
        assertFailsWith<IllegalArgumentException>{ItemRollbackImagesCodec.decode(image.record,rehash(version))}
        val input=DataInputStream(ByteArrayInputStream(body));input.readInt();input.skipBytes(32);input.readInt();input.readUnsignedByte();input.readUTF();repeat(4){input.readInt()}
        val countOffset=body.size-input.available();ByteBuffer.wrap(body).putInt(countOffset,-1)
        assertFailsWith<IllegalArgumentException>{ItemRollbackImagesCodec.decode(image.record,rehash(body))}
    }
    @Test fun itemAndWholePayloadBudgetsAreRefusedWithoutTruncation() {
        val big=spare.copy(itemData=BinaryPayload.of(ByteArray(1024*1024+1)))
        val record=record();val map=original().slots.toMutableMap();map[a.copy(index=1)]=big
        assertFailsWith<IllegalArgumentException>{ItemRollbackImagesCodec.encode(ItemRollbackImages(record,InventorySnapshot(map)))}
        val blob=spare.copy(itemData=BinaryPayload.of(ByteArray(1024*1024)))
        val wide=linkedMapOf<ItemSlotAddress,ItemStackSnapshot>();listOf(a.owner,b.owner).forEach { owner->repeat(10){wide[ItemSlotAddress(owner,it)]=blob} };wide[a]=ItemStackSnapshot.EMPTY;wide[b]=item
        assertFailsWith<IllegalArgumentException>{ItemRollbackImages(record,InventorySnapshot(wide))}
    }
    @Test fun reverseChainIncludesTheCompleteUnchangedInventory() {
        val c=ItemSlotAddress(ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"),BlockPosition(2,64,0)),0)
        val newest=ItemRollbackEntry(UUID.randomUUID(),listOf(ItemSlotChange(b,item,ItemStackSnapshot.EMPTY),ItemSlotChange(c,ItemStackSnapshot.EMPTY,item)))
        val oldest=record().entries.single();val record=ItemRollbackRecord(UUID.randomUUID(),1,ItemRollbackPhase.PREPARED,listOf(newest,oldest))
        val map=original().slots.toMutableMap();map[b]=ItemStackSnapshot.EMPTY;map[c]=item;map[c.copy(index=1)]=spare
        val image=ItemRollbackImages(record,InventorySnapshot(map));assertEquals(item,image.expected.slots[a]);assertEquals(ItemStackSnapshot.EMPTY,image.expected.slots[b]);assertEquals(ItemStackSnapshot.EMPTY,image.expected.slots[c]);assertEquals(spare,image.expected.slots[c.copy(index=1)])
    }
}
