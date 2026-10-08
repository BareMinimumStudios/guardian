package com.bareminimumstudios.guardian.lookup
import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import kotlin.test.*
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
class ContainerHistoryFormatterTest {
    companion object { init { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap() } }
    private val actor = ActorIdentity.Player(UUID.randomUUID(), "poke0")
    private val owner = ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"), BlockPosition(-281, 111, -26))
    private fun coal(count: Int) = ItemStackSnapshot(ResourceId.parse("minecraft:coal"), count, BinaryPayload.of(byteArrayOf(1)))
    @Test fun showsContainerNetCountWithoutCursorUuidOrRawSlots() {
        val tx = ContainerTransactionSnapshot(UUID.randomUUID(), 1000, actor, 1, ContainerAction.PICKUP,
            listOf(ItemSlotChange(ItemSlotAddress(owner, 1), coal(2), coal(8)),
                ItemSlotChange(ItemSlotAddress(ItemSlotOwner.Cursor(actor.uuid), 0), coal(7), coal(1))))
        val lines = ContainerHistoryFormatter.lines(listOf(tx), 2000).map { it.string }
        assertTrue(lines.any { "poke0 added 6 coal to container @ -281, 111, -26" in it })
        assertTrue(lines.none { "cursor" in it || "slot" in it || tx.transactionId.toString() in it })
    }
    @Test fun labelsHopperAndRespectsInspectedContainer() {
        val dest = owner.copy(position = BlockPosition(-281, 110, -26))
        val tx = ContainerTransactionSnapshot(UUID.randomUUID(), 1000, ActorIdentity.System("minecraft:hopper"), 0, ContainerAction.HOPPER_TRANSFER,
            listOf(ItemSlotChange(ItemSlotAddress(owner, 0), coal(2), coal(1)), ItemSlotChange(ItemSlotAddress(dest, 0), ItemStackSnapshot.EMPTY, coal(1))))
        val lines = ContainerHistoryFormatter.lines(listOf(tx), 2000, dest).map { it.string }
        assertTrue(lines.any { "Hopper added 1 coal" in it })
        assertTrue(lines.none { "removed" in it })
    }
}
