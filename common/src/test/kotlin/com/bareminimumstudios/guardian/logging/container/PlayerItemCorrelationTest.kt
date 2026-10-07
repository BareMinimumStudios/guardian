package com.bareminimumstudios.guardian.logging.container

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import kotlin.test.*

class PlayerItemCorrelationTest {
    private val actor = ActorIdentity.Player(UUID.randomUUID(), "Tester")
    private val cursor = ItemSlotAddress(ItemSlotOwner.Cursor(actor.uuid), 0)
    private val main = ItemSlotAddress(ItemSlotOwner.PlayerInventory(actor.uuid), 0)
    private val offhand = ItemSlotAddress(ItemSlotOwner.PlayerInventory(actor.uuid), 40)
    private val block = ItemSlotAddress(ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"), BlockPosition(1, 64, 1)), 0)
    private fun item(count: Int) = ItemStackSnapshot(ResourceId.parse("minecraft:diamond"), count, BinaryPayload.of(byteArrayOf(1)))
    @Test fun closingCorrelatesCursorReturnWithPlayerInventoryAndOriginalContainer() {
        val before = InventorySnapshot(mapOf(cursor to item(8), main to ItemStackSnapshot.EMPTY, block to ItemStackSnapshot.EMPTY))
        val after = InventorySnapshot(mapOf(cursor to ItemStackSnapshot.EMPTY, main to item(8), block to ItemStackSnapshot.EMPTY))
        val value = assertNotNull(ContainerTransactionCorrelation(actor, 1, ContainerAction.CLOSE, before).finish(after, true))
        assertEquals(ContainerAction.CLOSE, value.action); assertEquals(listOf(cursor, main), value.changes.map { it.address })
        assertEquals(listOf(block.owner), value.containers)
    }
    @Test fun offhandSwapIsOneStandaloneItemTransaction() {
        val before = InventorySnapshot(mapOf(main to item(8), offhand to item(3)))
        val after = InventorySnapshot(mapOf(main to item(3), offhand to item(8)))
        val value = assertNotNull(ContainerTransactionCorrelation(actor, 0, ContainerAction.SWAP_OFFHAND, before).finish(after, true))
        assertEquals(2, value.changes.size); assertTrue(value.containers.isEmpty()); assertEquals(ActionType.ITEM_CHANGE, ContainerAuditEntry(value).action)
    }
    @Test fun rejectedOrUnchangedDropCreatesNoAuditEntry() {
        val before = InventorySnapshot(mapOf(main to item(8)))
        assertNull(ContainerTransactionCorrelation(actor, 0, ContainerAction.DROP_ONE, before).finish(before, true))
        assertNull(ContainerTransactionCorrelation(actor, 0, ContainerAction.DROP_STACK, before).finish(InventorySnapshot(mapOf(main to ItemStackSnapshot.EMPTY)), false))
    }
    @Test fun slotsCannotBeAttributedToADifferentPlayerOrMissingContainer() {
        val other = ItemSlotAddress(ItemSlotOwner.PlayerInventory(UUID.randomUUID()), 0)
        assertFailsWith<IllegalArgumentException> { ContainerTransactionSnapshot(UUID.randomUUID(), 1, actor, 0, ContainerAction.DROP_ONE, listOf(ItemSlotChange(other, item(1), ItemStackSnapshot.EMPTY))) }
        assertFailsWith<IllegalArgumentException> { ContainerTransactionSnapshot(UUID.randomUUID(), 1, actor, 0, ContainerAction.CLOSE, listOf(ItemSlotChange(block, item(1), ItemStackSnapshot.EMPTY)), emptyList()) }
    }
}
