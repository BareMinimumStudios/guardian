package com.bareminimumstudios.guardian.checkpoint

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.*
import com.bareminimumstudios.guardian.storage.codec.*
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.test.*

class EquipmentRecoveryCodecTest {
    private val player=UUID.randomUUID()
    private val diamond=ItemStackSnapshot(ResourceId.parse("minecraft:diamond"),1,BinaryPayload.of(byteArrayOf(1,2,3)))
    private fun record(owner:ItemSlotOwner.PlayerInventory):ItemRollbackRecord {
        val source=UUID.randomUUID()
        return ItemRollbackRecord(UUID.randomUUID(),100,ItemRollbackPhase.PREPARED,listOf(ItemRollbackEntry(source,listOf(
            ItemSlotChange(ItemSlotAddress(owner,owner.layout?.normalSlots ?: 0),diamond,ItemStackSnapshot.EMPTY)))),
            ItemRecoveryPurpose.CHECKPOINT_RESTORE,checkpointOf=source)
    }
    @Test fun equipmentOwnerTopologySurvivesChangesAndDurableRecoveryImages() {
        val owner=ItemSlotOwner.PlayerInventory(player,PlayerStorageLayout(41,setOf("trinkets"),listOf(EquipmentSlotAddress("trinkets","hand/ring/0"))))
        val plan=record(owner);val changes=plan.entries.single().changes
        val bytes=ContainerChangesCodec.encode(changes)
        assertEquals(0x47435434,ByteBuffer.wrap(bytes).int)
        assertEquals(changes,ContainerChangesCodec.decode(bytes))
        val image=InventorySnapshot((0 until 42).associate { ItemSlotAddress(owner,it) to ItemStackSnapshot.EMPTY })
        val saved=ItemRollbackImages(plan,image);val encoded=ItemRollbackImagesCodec.encode(saved)
        assertEquals(0x47494932,ByteBuffer.wrap(encoded).int)
        val decoded=ItemRollbackImagesCodec.decode(plan,encoded)
        assertEquals(saved.original.slots,decoded.original.slots)
        assertEquals(saved.expected.slots,decoded.expected.slots)
        val other=ItemSlotOwner.PlayerInventory(player,PlayerStorageLayout(41,setOf("trinkets"),listOf(EquipmentSlotAddress("trinkets","head/hat/0"))))
        val changed=ItemRollbackRecord(plan.operationId,plan.createdAt,plan.phase,plan.entries.map { entry ->
            ItemRollbackEntry(entry.transactionId,entry.changes.map { ItemSlotChange(ItemSlotAddress(other,it.address.index),it.before,it.after) })
        },plan.purpose,checkpointOf=plan.checkpointOf)
        assertFails { ItemRollbackImagesCodec.decode(changed,encoded) }
        assertFails { ItemRollbackImages(plan,InventorySnapshot(image.slots.filterKeys { it.index<41 })) }
    }
    @Test fun aLargeTopologyIsStoredOnceInsteadOfOncePerSlot() {
        val names=(0 until 215).map { EquipmentSlotAddress("accessories","cosmetic/"+"x".repeat(100)+"/$it") }
        val owner=ItemSlotOwner.PlayerInventory(player,PlayerStorageLayout(41,setOf("accessories"),names))
        val plan=record(owner)
        val image=InventorySnapshot((0 until 256).associate { ItemSlotAddress(owner,it) to ItemStackSnapshot.EMPTY })
        val bytes=ItemRollbackImagesCodec.encode(ItemRollbackImages(plan,image))
        assertTrue(bytes.size<100000,"Topology must not be duplicated across all 256 slots")
        assertEquals(image.slots,ItemRollbackImagesCodec.decode(plan,bytes).original.slots)
    }
    @Test fun normalInventoryRecoveryStillUsesItsOriginalWireFormats() {
        val owner=ItemSlotOwner.PlayerInventory(player);val plan=record(owner)
        assertEquals(0x47435431,ByteBuffer.wrap(ContainerChangesCodec.encode(plan.entries.single().changes)).int)
        val image=InventorySnapshot((0 until 41).associate { ItemSlotAddress(owner,it) to ItemStackSnapshot.EMPTY })
        val bytes=ItemRollbackImagesCodec.encode(ItemRollbackImages(plan,image))
        assertEquals(0x47494931,ByteBuffer.wrap(bytes).int)
        assertEquals(image.slots,ItemRollbackImagesCodec.decode(plan,bytes).original.slots)
    }
}
