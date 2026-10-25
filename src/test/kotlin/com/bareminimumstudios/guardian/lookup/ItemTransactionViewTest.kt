package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import kotlin.test.*

class ItemTransactionViewTest {
    private val actor=ActorIdentity.Player(UUID.randomUUID(),"Reader")
    private val owner=ItemSlotOwner.PlayerInventory(actor.uuid)
    private fun row(count: Int=1,bytes: Int=1)=ContainerTransactionSnapshot(UUID.randomUUID(),1,actor,0,ContainerAction.QUICK_MOVE,
        (0 until count).map { ItemSlotChange(ItemSlotAddress(owner,it),ItemStackSnapshot(ResourceId.parse("minecraft:coal"),3,BinaryPayload.of(ByteArray(bytes))),ItemStackSnapshot.EMPTY) })
    @Test fun emptyPageHasNoInventedHistory() {
        val page=ItemTransactionView(emptyList());assertNull(page.current);assertEquals(0,page.changePages);assertTrue(page.changes().isEmpty())
        page.transaction(1);page.changes(1);assertEquals(0,page.transaction)
    }
    @Test fun changedSlotPagesKeepPairsTogetherWithoutClaimingUnchangedSlots() {
        val row=row(33);val page=ItemTransactionView(listOf(row));assertEquals(3,page.changePages)
        assertEquals(row.changes.take(16),page.changes());page.changes(1);assertEquals(row.changes.subList(16,32),page.changes())
        page.changes(1);assertEquals(row.changes.takeLast(1),page.changes());page.changes(1);assertEquals(2,page.changePage)
        assertEquals(33,row.changes.size)
    }
    @Test fun changingTransactionResetsChangedSlotsAndRespectsBounds() {
        val rows=listOf(row(20),row(2));val page=ItemTransactionView(rows);page.changes(1);page.transaction(1)
        assertSame(rows[1],page.current);assertEquals(0,page.changePage);page.transaction(1);assertSame(rows[1],page.current)
        page.transaction(-1);page.transaction(-1);assertSame(rows[0],page.current)
    }
    @Test fun invalidNavigationCannotJumpOrOverflow() {
        val page=ItemTransactionView(listOf(row(20),row()));for(delta in listOf(0,2,-2,Int.MAX_VALUE,Int.MIN_VALUE)) { page.transaction(delta);page.changes(delta) }
        assertEquals(0,page.transaction);assertEquals(0,page.changePage)
    }
    @Test fun resultRowsAreCopiedAndSnapshotsRemainImmutable() {
        val rows=mutableListOf(row());val page=ItemTransactionView(rows);rows.clear();assertEquals(1,page.rows.size)
        val payload=page.current!!.changes.single().before.itemData!!;payload.copyBytes()[0]=9
        assertContentEquals(byteArrayOf(0),payload.copyBytes())
    }
    @Test fun rowAndChangeBudgetsRefuseOversizedPages() {
        assertFailsWith<IllegalArgumentException> { ItemTransactionView(List(51) { row() }) }
        assertFailsWith<IllegalArgumentException> { ItemTransactionView(List(5) { row(1000) }) }
    }
    @Test fun snapshotBudgetIsCheckedWithoutDecodingRecordedComponents() {
        assertFailsWith<IllegalArgumentException> { ItemTransactionView(listOf(row(9,1024*1024))) }
        assertEquals(1,ItemTransactionView(listOf(row(1,20))).changes().size)
    }
}
