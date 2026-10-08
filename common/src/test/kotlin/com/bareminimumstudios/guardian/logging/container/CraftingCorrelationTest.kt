package com.bareminimumstudios.guardian.logging.container
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.codec.ContainerChangesCodec
import java.util.UUID
import kotlin.test.*
class CraftingCorrelationTest {
    private val actor = ActorIdentity.Player(UUID.randomUUID(), "Crafter")
    private val grid = ItemSlotAddress(ItemSlotOwner.CraftingGrid(actor.uuid, 7), 0)
    private val cursor = ItemSlotAddress(ItemSlotOwner.Cursor(actor.uuid), 0)
    private fun item(id: String, count: Int, data: Int = 1) = ItemStackSnapshot(ResourceId.parse(id), count, BinaryPayload.of(byteArrayOf(data.toByte())))
    private fun snap(input: ItemStackSnapshot, output: ItemStackSnapshot) = InventorySnapshot(mapOf(grid to input, cursor to output))
    @Test fun craftStoresConsumedInputAndActualOutputWithoutPreview() {
        val tx = ContainerTransactionCorrelation(actor,7,ContainerAction.PICKUP,snap(item("minecraft:oak_log",2),ItemStackSnapshot.EMPTY))
            .finish(snap(item("minecraft:oak_log",1),item("minecraft:oak_planks",4,9)),true,true)!!
        assertEquals(ContainerAction.CRAFT,tx.action);assertEquals(2,tx.changes.size)
        assertEquals(tx.changes,ContainerChangesCodec.decode(ContainerChangesCodec.encode(tx.changes)))
        assertEquals(9,tx.changes.last().after.itemData!!.copyBytes().single().toInt())
    }
    @Test fun canceledAndUnchangedTakesDoNotInventCrafts() {
        val before=snap(item("minecraft:oak_log",1),ItemStackSnapshot.EMPTY)
        assertNull(ContainerTransactionCorrelation(actor,7,ContainerAction.PICKUP,before).finish(before,true,true))
        assertNull(ContainerTransactionCorrelation(actor,7,ContainerAction.PICKUP,before).finish(snap(ItemStackSnapshot.EMPTY,item("minecraft:oak_planks",4)),false,true))
    }
    @Test fun recipePlacementIsNotCraftingAndRetainsTableContext() {
        val table=ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"),BlockPosition(1,64,2))
        val tx=ContainerTransactionCorrelation(actor,7,ContainerAction.RECIPE_PLACE,snap(ItemStackSnapshot.EMPTY,item("minecraft:oak_log",1)),contexts=listOf(table))
            .finish(snap(item("minecraft:oak_log",1),ItemStackSnapshot.EMPTY),true)!!
        assertEquals(ContainerAction.RECIPE_PLACE,tx.action);assertEquals(listOf(table),tx.containers)
    }
    @Test fun rejectsForeignGridAndWrongMenuOwnership() {
        fun check(owner:ItemSlotOwner)=assertFailsWith<IllegalArgumentException> {
            ContainerTransactionSnapshot(UUID.randomUUID(),1,actor,7,ContainerAction.CRAFT,listOf(ItemSlotChange(ItemSlotAddress(owner,0),item("minecraft:oak_log",1),ItemStackSnapshot.EMPTY)))
        }
        check(ItemSlotOwner.CraftingGrid(UUID.randomUUID(),7));check(ItemSlotOwner.CraftingGrid(actor.uuid,8))
    }
    @Test fun oldPayloadRemainsReadableAndCannotSmuggleNewOwners() {
        val change=ItemSlotChange(cursor,ItemStackSnapshot.EMPTY,item("minecraft:stone",1))
        val bytes=ContainerChangesCodec.encode(listOf(change));assertEquals(0x31,bytes[3].toInt())
        assertEquals(listOf(change),ContainerChangesCodec.decode(bytes))
        val newer=ContainerChangesCodec.encode(listOf(change.copy(address=grid)));assertEquals(0x32,newer[3].toInt())
        newer[3]=0x31;assertFailsWith<IllegalArgumentException>{ContainerChangesCodec.decode(newer)}
    }
    @Test fun recipeRemainderAndOutputAreInOneTransaction() {
        val before=snap(item("minecraft:milk_bucket",1),ItemStackSnapshot.EMPTY)
        val tx=ContainerTransactionCorrelation(actor,7,ContainerAction.QUICK_MOVE,before).finish(snap(item("minecraft:bucket",1),item("minecraft:cake",1)),true,true)!!
        assertEquals(ResourceId.parse("minecraft:bucket"),tx.changes.first().after.itemId)
        assertEquals(ContainerAction.CRAFT,tx.action)
    }
}
