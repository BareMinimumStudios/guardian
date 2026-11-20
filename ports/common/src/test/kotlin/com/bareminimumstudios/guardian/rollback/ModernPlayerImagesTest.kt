package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.codec.ItemRollbackImagesCodec
import java.util.UUID
import kotlin.test.*

class ModernPlayerImagesTest {
    private val player=ItemSlotOwner.PlayerInventory(UUID.randomUUID())
    private val container=ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"),BlockPosition(0,64,0))
    private val coal=ItemStackSnapshot(ResourceId.parse("minecraft:coal"),9,BinaryPayload.of(byteArrayOf(1,2)))
    private val equipment=ItemStackSnapshot(ResourceId.parse("minecraft:leather_horse_armor"),1,BinaryPayload.of(byteArrayOf(3,4)))
    private fun record()=ItemRollbackRecord(UUID.randomUUID(),1,ItemRollbackPhase.PREPARED,listOf(ItemRollbackEntry(UUID.randomUUID(),listOf(
        ItemSlotChange(ItemSlotAddress(player,0),coal,ItemStackSnapshot.EMPTY),
        ItemSlotChange(ItemSlotAddress(container,0),ItemStackSnapshot.EMPTY,coal)))))
    private fun observed(size:Int)=InventorySnapshot((0 until size).associate { ItemSlotAddress(player,it) to if(it>=41)equipment else ItemStackSnapshot.EMPTY } + (ItemSlotAddress(container,0) to coal))

    @Test fun completeModernImageRetainsBodyAndSaddleAcrossJournalRoundTrip() {
        val record=record();val image=ItemRollbackImages(record,observed(43))
        val decoded=ItemRollbackImagesCodec.decode(record,ItemRollbackImagesCodec.encode(image))
        assertEquals(44,decoded.expected.slots.size)
        assertEquals(coal,decoded.expected.slots[ItemSlotAddress(player,0)])
        for(slot in 41..42)assertEquals(equipment,decoded.expected.slots[ItemSlotAddress(player,slot)])
    }
    @Test fun unknownOrGappedModernLayoutsAreRejected() {
        for(size in listOf(40,42,44))assertFailsWith<IllegalArgumentException> { ItemRollbackImages(record(),observed(size)) }
        assertFailsWith<IllegalArgumentException> { ItemRollbackImages(record(),InventorySnapshot(observed(43).slots-ItemSlotAddress(player,12))) }
    }
}
