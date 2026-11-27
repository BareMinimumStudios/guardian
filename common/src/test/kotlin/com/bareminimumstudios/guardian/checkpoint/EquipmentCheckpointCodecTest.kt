package com.bareminimumstudios.guardian.checkpoint

import java.nio.ByteBuffer
import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import kotlin.test.*

class EquipmentCheckpointCodecTest {
    private val player=UUID.randomUUID()
    private fun checkpoint(equipment:PlayerEquipmentSnapshot?=null):InventoryCheckpoint {
        val owner=ItemSlotOwner.PlayerInventory(player)
        return InventoryCheckpoint(UUID.randomUUID(),player,"Alex",100,"1.21.1",InventoryCheckpointReason.MANUAL,
            ResourceId.parse("minecraft:overworld"),BlockPosition(1,64,2),
            InventorySnapshot((0 until 41).associate { ItemSlotAddress(owner,it) to ItemStackSnapshot.EMPTY }),equipment)
    }
    @Test fun originalCheckpointEncodingRemainsReadableAndByteStable() {
        val original=checkpoint();val bytes=InventoryCheckpointCodec.encode(original)
        assertEquals(0x47435031,ByteBuffer.wrap(bytes).int)
        val decoded=InventoryCheckpointCodec.decode(bytes)
        assertNull(decoded.equipment)
        assertContentEquals(bytes,InventoryCheckpointCodec.encode(decoded))
    }
    @Test fun namedEquipmentRoundTripsEmptySlotsComponentsAndCoverage() {
        val equipment=PlayerEquipmentSnapshot(mapOf("trinkets" to "3.10.0","curios" to "9"),mapOf(
            EquipmentSlotAddress("trinkets","hand/ring/0") to ItemStackSnapshot(ResourceId.parse("minecraft:diamond"),1,BinaryPayload.of(byteArrayOf(1,2,3))),
            EquipmentSlotAddress("curios","cosmetic/head/0") to ItemStackSnapshot.EMPTY))
        val original=checkpoint(equipment);val bytes=InventoryCheckpointCodec.encode(original)
        assertEquals(0x47435032,ByteBuffer.wrap(bytes).int)
        val decoded=InventoryCheckpointCodec.decode(bytes)
        assertEquals(equipment,decoded.equipment);assertEquals(43,decoded.slotCount)
        assertContentEquals(bytes,InventoryCheckpointCodec.encode(decoded))
        bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
        assertFailsWith<IllegalArgumentException> { InventoryCheckpointCodec.decode(bytes) }
    }
    @Test fun equipmentRestorationCannotFallBackToReplacingOnlyNormalSlots() {
        val saved=checkpoint(PlayerEquipmentSnapshot(mapOf("trinkets" to "3.10.0"),emptyMap()))
        val before=InventoryCheckpoint(UUID.randomUUID(),player,"Alex",200,"1.21.1",InventoryCheckpointReason.BEFORE_RESTORE,
            saved.dimension,saved.position,saved.inventory,saved.equipment)
        assertFailsWith<CheckpointRestoreRefusedException> { CheckpointRestoreSource(saved).record(UUID.randomUUID(),200,before) }
    }
}
