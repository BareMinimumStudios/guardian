package com.bareminimumstudios.guardian.checkpoint

import com.bareminimumstudios.guardian.domain.*
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.*

class InventoryCheckpointTest {
    private val player=UUID.randomUUID()
    private val owner=ItemSlotOwner.PlayerInventory(player)
    private val bytes=byteArrayOf(1,2,3,4)
    private val coal=ItemStackSnapshot(ResourceId.parse("minecraft:coal"),9,BinaryPayload.of(bytes))
    private fun image(size:Int)=InventorySnapshot((0 until size).associate { index ->
        ItemSlotAddress(owner,index) to if(index==2)coal else ItemStackSnapshot.EMPTY
    })
    private fun checkpoint(size:Int=41)=InventoryCheckpoint(UUID.randomUUID(),player,"Alex",100,"1.21.1",
        InventoryCheckpointReason.MANUAL,ResourceId.parse("minecraft:overworld"),BlockPosition(1,64,2),image(size))

    @Test fun fullImagesRoundTripEmptySlotsAndOpaqueComponents() {
        for(size in listOf(41,43)) {
            val original=checkpoint(size);val encoded=InventoryCheckpointCodec.encode(original)
            val decoded=InventoryCheckpointCodec.decode(encoded)
            assertEquals(original.checkpointId,decoded.checkpointId)
            assertEquals(original.playerId,decoded.playerId)
            assertEquals(original.capturedAt,decoded.capturedAt)
            assertEquals(original.reason,decoded.reason)
            assertEquals(original.position,decoded.position)
            assertEquals(original.inventory.slots,decoded.inventory.slots)
        }
    }

    @Test fun checksumAndTrailingBytesCannotBeIgnored() {
        val encoded=InventoryCheckpointCodec.encode(checkpoint())
        val changed=encoded.copyOf();changed[40]=(changed[40].toInt() xor 1).toByte()
        assertFails { InventoryCheckpointCodec.decode(changed) }
        assertFails { InventoryCheckpointCodec.decode(encoded.copyOf(40)) }
        val payload=encoded.copyOf(encoded.size-32)+byteArrayOf(0)
        assertFails { InventoryCheckpointCodec.decode(payload+MessageDigest.getInstance("SHA-256").digest(payload)) }
    }

    @Test fun replacementUsesAllPersistentSlotsAndRejectsOtherShapes() {
        val saved=checkpoint()
        assertTrue(saved.replacementChanges(saved.inventory).isEmpty())
        val address=ItemSlotAddress(owner,2)
        val current=InventorySnapshot(saved.inventory.slots+(address to ItemStackSnapshot.EMPTY))
        assertEquals(listOf(ItemSlotChange(address,coal,ItemStackSnapshot.EMPTY)),saved.replacementChanges(current))
        assertFails { saved.replacementChanges(image(43)) }
    }

    @Test fun snapshotDoesNotRetainMutableSlotMaps() {
        val slots=image(41).slots.toMutableMap()
        val saved=InventoryCheckpoint(UUID.randomUUID(),player,"Alex",100,"1.21.1",InventoryCheckpointReason.MANUAL,
            ResourceId.parse("minecraft:overworld"),BlockPosition(0,64,0),InventorySnapshot(slots))
        slots.clear()
        assertEquals(41,saved.inventory.slots.size)
        assertFailsWith<UnsupportedOperationException> { (saved.inventory.slots as MutableMap).clear() }
    }
}
