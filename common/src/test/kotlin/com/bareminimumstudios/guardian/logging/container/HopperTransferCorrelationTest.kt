package com.bareminimumstudios.guardian.logging.container

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import kotlin.test.*

class HopperTransferCorrelationTest {
    private val dim = ResourceId.parse("minecraft:overworld")
    private val source = ItemSlotOwner.BlockContainer(dim, BlockPosition(1, 65, 1))
    private val destination = ItemSlotOwner.BlockContainer(dim, BlockPosition(1, 64, 1))
    private val sourceSlot = ItemSlotAddress(source, 0)
    private val destinationSlot = ItemSlotAddress(destination, 0)
    private fun item(count: Int, data: Byte = 1) = if (count == 0) ItemStackSnapshot.EMPTY else ItemStackSnapshot(ResourceId.parse("minecraft:diamond"), count, BinaryPayload.of(byteArrayOf(data)))
    private fun frame(sourceCount: Int, destinationCount: Int) = InventorySnapshot(mapOf(sourceSlot to item(sourceCount), destinationSlot to item(destinationCount)))
    private fun begin(before: InventorySnapshot) = HopperTransferCorrelation(before, setOf(source), setOf(destination))

    @Test fun correlatesSourceAndDestinationWithSystemAttribution() {
        val value = assertNotNull(begin(frame(3, 0)).finish(frame(2, 1)))
        assertEquals(ActorIdentity.System("minecraft:hopper"), value.actor)
        assertEquals(ContainerAction.HOPPER_TRANSFER, value.action)
        assertEquals(2, value.changes.size); assertEquals(setOf(source, destination), value.containers.toSet())
        assertEquals(ActionType.CONTAINER_CHANGE, ContainerAuditEntry(value).action)
    }
    @Test fun unchangedOrRestoredFailedAttemptCreatesNoTransaction() {
        val before = frame(3, 64)
        assertNull(begin(before).finish(frame(3, 64)))
        assertNull(begin(frame(0, 0)).finish(frame(0, 0)))
    }
    @Test fun rejectsUnbalancedRemovalAndWrongDirection() {
        assertFailsWith<IllegalArgumentException> { begin(frame(3, 0)).finish(frame(2, 0)) }
        assertFailsWith<IllegalArgumentException> { begin(frame(2, 1)).finish(frame(3, 0)) }
    }
    @Test fun rejectsComponentChangesAndOneSidedInsertion() {
        val after = InventorySnapshot(mapOf(sourceSlot to item(2), destinationSlot to item(1, 2)))
        assertFailsWith<IllegalArgumentException> { begin(frame(3, 0)).finish(after) }
        assertFailsWith<IllegalArgumentException> { begin(frame(3, 0)).finish(frame(3, 1)) }
    }
    @Test fun retainsDoubleChestContextAndCopiesOwnerSets() {
        val otherHalf = ItemSlotOwner.BlockContainer(dim, BlockPosition(2, 65, 1))
        val owners = mutableSetOf(source, otherHalf)
        val before = InventorySnapshot(frame(3, 7).slots + (ItemSlotAddress(otherHalf, 0) to item(2)))
        val correlation = HopperTransferCorrelation(before, owners, mutableSetOf(destination))
        owners.clear()
        val after = InventorySnapshot(frame(3, 8).slots + (ItemSlotAddress(otherHalf, 0) to item(1)))
        val value = assertNotNull(correlation.finish(after))
        assertEquals(setOf(source, otherHalf, destination), value.containers.toSet())
        assertEquals(2, value.changes.size)
        assertFailsWith<IllegalStateException> { correlation.finish(after) }
    }
    @Test fun rejectsOverlappingOwnersAndPlayerSlots() {
        assertFailsWith<IllegalArgumentException> { HopperTransferCorrelation(frame(3, 0), setOf(source), setOf(source)) }
        val player = ItemSlotAddress(ItemSlotOwner.PlayerInventory(UUID.randomUUID()), 0)
        assertFailsWith<IllegalArgumentException> { begin(InventorySnapshot(frame(3, 0).slots + (player to item(1)))) }
        assertFailsWith<IllegalArgumentException> { ContainerTransactionSnapshot(UUID.randomUUID(), 1, ActorIdentity.System("minecraft:hopper"), 0, ContainerAction.HOPPER_TRANSFER,
            listOf(ItemSlotChange(player, item(1), ItemStackSnapshot.EMPTY))) }
    }
    @Test fun rejectsTopologyChanges() {
        assertFailsWith<IllegalArgumentException> { begin(frame(3, 0)).finish(InventorySnapshot(mapOf(sourceSlot to item(2)))) }
    }
}
