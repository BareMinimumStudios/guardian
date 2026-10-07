package com.bareminimumstudios.guardian.logging.container

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import kotlin.test.*

class ContainerTransactionCorrelationTest {
    private val player = UUID.randomUUID()
    private val actor = ActorIdentity.Player(player, "Tester")
    private val container = ItemSlotAddress(ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"), BlockPosition(1, 64, 2)), 0)
    private val inventory = ItemSlotAddress(ItemSlotOwner.PlayerInventory(player), 9)
    private val cursor = ItemSlotAddress(ItemSlotOwner.Cursor(player), 0)
    private fun item(count: Int, payload: Byte = 1) = ItemStackSnapshot(ResourceId.parse("minecraft:diamond"), count, BinaryPayload.of(byteArrayOf(payload)))
    private fun frame(vararg values: Pair<ItemSlotAddress, ItemStackSnapshot>) = InventorySnapshot(mapOf(*values))

    @Test fun correlatesShiftClickUnderOneId() {
        val id = UUID.randomUUID()
        val bracket = ContainerTransactionCorrelation(actor, 1, ContainerAction.QUICK_MOVE,
            frame(container to item(8), inventory to ItemStackSnapshot.EMPTY, cursor to ItemStackSnapshot.EMPTY), 100, id)
        val result = assertNotNull(bracket.finish(frame(container to ItemStackSnapshot.EMPTY, inventory to item(8), cursor to ItemStackSnapshot.EMPTY), true))
        assertEquals(id, result.transactionId); assertEquals(100L, result.timestampEpochMillis)
        assertEquals(listOf(container, inventory), result.changes.map { it.address })
        assertFailsWith<IllegalStateException> { bracket.finish(frame(), true) }
    }
    @Test fun cancelledActionCreatesNoTransaction() {
        assertNull(ContainerTransactionCorrelation(actor, 1, ContainerAction.PICKUP, frame(container to item(1))).finish(frame(), false))
    }
    @Test fun unchangedActionCreatesNoTransaction() {
        val value = frame(container to item(1))
        assertNull(ContainerTransactionCorrelation(actor, 1, ContainerAction.PICKUP, value).finish(value, true))
    }
    @Test fun componentOnlyChangeIsRecorded() {
        val result = assertNotNull(ContainerTransactionCorrelation(actor, 1, ContainerAction.SWAP, frame(container to item(1))).finish(frame(container to item(1, 2)), true))
        assertEquals(1, result.changes.size)
    }
    @Test fun topologyChangeIsRejected() {
        assertFailsWith<IllegalArgumentException> { ContainerTransactionCorrelation(actor, 1, ContainerAction.CLOSE, frame(container to item(1))).finish(frame(cursor to item(1)), true) }
    }
    @Test fun inputAndExposedCollectionsCannotMutateSnapshots() {
        val values = mutableMapOf(container to item(1))
        val snapshot = InventorySnapshot(values); values.clear()
        assertEquals(1, snapshot.slots.size)
        assertFailsWith<UnsupportedOperationException> { (snapshot.slots as MutableMap).clear() }
        val changes = mutableListOf(ItemSlotChange(container, item(1), ItemStackSnapshot.EMPTY))
        val transaction = ContainerTransactionSnapshot(UUID.randomUUID(), 1, actor, 1, ContainerAction.THROW, changes)
        changes.clear(); assertEquals(1, transaction.changes.size)
        assertFailsWith<UnsupportedOperationException> { (transaction.changes as MutableList).clear() }
    }
    @Test fun payloadIsDefensivelyCopiedAndCountRemainsSeparate() {
        val bytes = byteArrayOf(1); val payload = BinaryPayload.of(bytes); bytes[0] = 2
        val first = ItemStackSnapshot(ResourceId.parse("minecraft:diamond"), 1, payload)
        val second = first.copy(count = 2)
        payload.copyBytes()[0] = 3
        assertEquals(1.toByte(), first.itemData!!.copyBytes()[0]); assertNotEquals(first, second)
        assertEquals(first.itemData, second.itemData)
    }
    @Test fun invalidStackAndSlotFormsAreRejected() {
        assertFailsWith<IllegalArgumentException> { ItemStackSnapshot(null, 1, null) }
        assertFailsWith<IllegalArgumentException> { ItemStackSnapshot(ResourceId.parse("minecraft:stone"), 0, null) }
        assertFailsWith<IllegalArgumentException> { ItemSlotAddress(ItemSlotOwner.Cursor(player), 1) }
        assertFailsWith<IllegalArgumentException> { ItemSlotAddress(ItemSlotOwner.PlayerInventory(player), -1) }
    }
}
