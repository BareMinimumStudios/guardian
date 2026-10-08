package com.bareminimumstudios.guardian.rollback
import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import kotlin.test.*
class ContainerRollbackPlannerTest {
    private val dim=ResourceId.parse("minecraft:overworld")
    private val a=ItemSlotOwner.BlockContainer(dim,BlockPosition(1,64,1))
    private val b=ItemSlotOwner.BlockContainer(dim,BlockPosition(2,64,1))
    private val actor=ActorIdentity.Player(UUID.randomUUID(),"Tester")
    private fun coal(n:Int,patch:Int=1)=if(n==0) ItemStackSnapshot.EMPTY else ItemStackSnapshot(ResourceId.parse("minecraft:coal"),n,BinaryPayload.of(byteArrayOf(patch.toByte())))
    private fun tx(time:Long,source:ItemSlotOwner=a,dest:ItemSlotOwner=b,before:Int=2,after:Int=1)=ContainerTransactionSnapshot(UUID.randomUUID(),time,actor,1,ContainerAction.QUICK_MOVE,listOf(
        ItemSlotChange(ItemSlotAddress(source,0),coal(before),coal(after)),ItemSlotChange(ItemSlotAddress(dest,0),coal(2-before),coal(2-after))))
    private fun live(rows:ContainerTransactionSnapshot)=InventorySnapshot(rows.changes.associate { it.address to it.after })
    @Test fun balancedTransferIsEligibleWithoutChangingLiveSnapshots() {
        val row=tx(2);val snapshot=live(row);val original=snapshot.slots.toMap()
        assertEquals(1,ContainerRollbackPlanner.plan(listOf(row),snapshot).eligible);assertEquals(original,snapshot.slots)
    }
    @Test fun reverseSimulationHandlesNewestFirstChain() {
        val old=tx(1);val newer=tx(2,before=1,after=0)
        assertEquals(2,ContainerRollbackPlanner.plan(listOf(newer,old),live(newer)).eligible)
    }
    @Test fun newerMismatchBlocksOlderSharedInventoryChain() {
        val old=tx(1);val newer=tx(2,before=1,after=0)
        val result=ContainerRollbackPlanner.plan(listOf(newer,old),live(old))
        assertEquals(listOf(ContainerPreviewReason.STATE_MISMATCH,ContainerPreviewReason.BLOCKED_CHAIN),result.entries.map{it.reason})
    }
    @Test fun componentsMustMatchEvenWhenItemAndCountMatch() {
        val row=tx(1);val slots=live(row).slots.toMutableMap();slots[ItemSlotAddress(a,0)]=coal(1,9)
        assertEquals(ContainerPreviewReason.STATE_MISMATCH,ContainerRollbackPlanner.plan(listOf(row),InventorySnapshot(slots)).entries.single().reason)
    }
    @Test fun unavailablePlayerBlocksWholeTransfer() {
        val player=ItemSlotOwner.PlayerInventory(actor.uuid);val row=tx(1,dest=player)
        assertEquals(ContainerPreviewReason.UNAVAILABLE_OWNER,ContainerRollbackPlanner.plan(listOf(row),live(row),setOf(player)).entries.single().reason)
    }
    @Test fun cursorOwnershipIsSkippedEvenIfCountsBalance() {
        val row=tx(1,dest=ItemSlotOwner.Cursor(actor.uuid))
        assertEquals(ContainerPreviewReason.UNSUPPORTED_OWNER,ContainerRollbackPlanner.plan(listOf(row),live(row)).entries.single().reason)
    }
    @Test fun regionMustContainBothPhysicalEndpoints() {
        val row=tx(1)
        assertEquals(ContainerPreviewReason.OUTSIDE_SCOPE,ContainerRollbackPlanner.plan(listOf(row),live(row),outsideScope=setOf(b)).entries.single().reason)
    }
    @Test fun creationAndComponentTransformationAreNotTransfers() {
        val original=tx(1)
        val row=ContainerTransactionSnapshot(UUID.randomUUID(),1,actor,1,ContainerAction.QUICK_MOVE,listOf(original.changes[0],original.changes[1].copy(after=coal(1,7))))
        assertEquals(ContainerPreviewReason.NONCONSERVING_ACTION,ContainerRollbackPlanner.plan(listOf(row),live(row)).entries.single().reason)
    }
    @Test fun craftingAndCreativeActionsAreExcluded() {
        for(action in listOf(ContainerAction.CRAFT,ContainerAction.RECIPE_PLACE,ContainerAction.CREATIVE_SET,ContainerAction.DROP_ONE,ContainerAction.CLOSE)) {
            val original=tx(1);val row=ContainerTransactionSnapshot(UUID.randomUUID(),1,actor,1,action,original.changes)
            assertEquals(ContainerPreviewReason.UNSUPPORTED_ACTION,ContainerRollbackPlanner.plan(listOf(row),live(row)).entries.single().reason)
        }
    }
    @Test fun sameTimestampSharedInventoryIsAmbiguous() {
        val old=tx(1);val newer=tx(1,before=1,after=0)
        assertEquals(listOf(ContainerPreviewReason.AMBIGUOUS_ORDER,ContainerPreviewReason.AMBIGUOUS_ORDER),ContainerRollbackPlanner.plan(listOf(newer,old),live(newer)).entries.map{it.reason})
    }
    @Test fun sameTimestampIndependentInventoriesRemainEligible() {
        val first=tx(1);val second=tx(1,source=a.copy(position=BlockPosition(3,64,1)),dest=b.copy(position=BlockPosition(4,64,1)))
        assertEquals(2,ContainerRollbackPlanner.plan(listOf(first,second),InventorySnapshot(live(first).slots+live(second).slots)).eligible)
    }
    @Test fun oneSkippedInventoryDoesNotBlockIndependentHistory() {
        val first=tx(2);val second=tx(1,source=a.copy(position=BlockPosition(3,64,1)),dest=b.copy(position=BlockPosition(4,64,1)))
        assertEquals(1,ContainerRollbackPlanner.plan(listOf(first,second),live(second)).eligible)
    }
    @Test fun missingRecordedSlotIsMismatchNotAnEmptySlot() {
        val row=tx(1);assertEquals(ContainerPreviewReason.STATE_MISMATCH,ContainerRollbackPlanner.plan(listOf(row),InventorySnapshot(emptyMap())).entries.single().reason)
    }
    @Test fun refusesDuplicateUnorderedAndOversizedPlans() {
        val row=tx(2)
        assertFailsWith<IllegalArgumentException>{ContainerRollbackPlanner.plan(listOf(row,row),live(row))}
        assertFailsWith<IllegalArgumentException>{ContainerRollbackPlanner.plan(listOf(tx(1),row),live(row))}
        assertFailsWith<IllegalArgumentException>{ContainerRollbackPlanner.plan((1..51).map{tx(it.toLong())}.reversed(),live(row))}
    }
}
