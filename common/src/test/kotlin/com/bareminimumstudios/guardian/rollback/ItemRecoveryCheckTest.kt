package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import kotlin.test.*

class ItemRecoveryCheckTest {
    private val dimension=ResourceId.parse("minecraft:overworld")
    private val left=ItemSlotAddress(ItemSlotOwner.BlockContainer(dimension,BlockPosition(1,64,1)),0)
    private val right=ItemSlotAddress(ItemSlotOwner.BlockContainer(dimension,BlockPosition(2,64,1)),0)
    private val item=ItemStackSnapshot(ResourceId.parse("minecraft:coal"),1,BinaryPayload.of(byteArrayOf(1)))
    private fun entry(source: ItemSlotAddress=left,destination: ItemSlotAddress=right)=ItemRollbackEntry(UUID.randomUUID(),listOf(ItemSlotChange(source,item,ItemStackSnapshot.EMPTY),ItemSlotChange(destination,ItemStackSnapshot.EMPTY,item)))
    private fun record(entries: List<ItemRollbackEntry> = listOf(entry()))=ItemRollbackRecord(UUID.randomUUID(),100,ItemRollbackPhase.RECOVERY_REQUIRED,entries)
    private fun observe(a: ItemStackSnapshot,b: ItemStackSnapshot): ItemRecoveryObservation {
        val check=ItemRecoveryCheck(record());check.accept(left.owner,InventorySnapshot(mapOf(left to a)));check.accept(right.owner,InventorySnapshot(mapOf(right to b)));return check.observe()
    }
    @Test fun reportsEveryNonCyclicObservationWithoutChangingJournal() {
        assertEquals(ItemRecoveryObservation.ORIGINAL,observe(ItemStackSnapshot.EMPTY,item))
        assertEquals(ItemRecoveryObservation.RESTORED,observe(item,ItemStackSnapshot.EMPTY))
        assertEquals(ItemRecoveryObservation.PARTIAL,observe(item,item))
        assertEquals(ItemRecoveryObservation.CONFLICT,observe(item.copy(itemData=BinaryPayload.of(byteArrayOf(2))),ItemStackSnapshot.EMPTY))
    }
    @Test fun identityChangeOrUnavailableOwnerLeavesRecoveryUnresolved() {
        val record=record();val check=ItemRecoveryCheck(record)
        check.accept(left.owner,InventorySnapshot(mapOf(left to ItemStackSnapshot.EMPTY)));check.accept(right.owner,InventorySnapshot(mapOf(right to item)))
        assertEquals(ItemRecoveryObservation.UNAVAILABLE,check.observe(setOf(right.owner)));assertEquals(ItemRollbackPhase.RECOVERY_REQUIRED,record.phase)
        val missing=ItemRecoveryCheck(record);missing.accept(left.owner,null);missing.accept(right.owner,InventorySnapshot(mapOf(right to item)));assertEquals(ItemRecoveryObservation.UNAVAILABLE,missing.observe())
    }
    @Test fun requiresCompleteObservationsAndRejectsDuplicateOrUnknownOwners() {
        val check=ItemRecoveryCheck(record());assertFailsWith<IllegalStateException> { check.observe() }
        check.accept(left.owner,InventorySnapshot(mapOf(left to item)));assertFailsWith<IllegalArgumentException> { check.accept(left.owner,null) }
        assertFailsWith<IllegalArgumentException> { check.accept(ItemSlotOwner.PlayerInventory(UUID.randomUUID()),null) }
        assertFailsWith<IllegalStateException> { check.observe() }
    }
    @Test fun acceptsOnlyWantedSlotsBelongingToTheRequestedOwner() {
        val check=ItemRecoveryCheck(record());assertEquals(setOf(left),check.addresses(left.owner))
        check.accept(left.owner,InventorySnapshot(mapOf(left to ItemStackSnapshot.EMPTY,right to item)));check.accept(right.owner,null)
        assertEquals(ItemRecoveryObservation.UNAVAILABLE,check.observe())
        assertFailsWith<UnsupportedOperationException> { (check.owners as MutableList).clear() }
    }
    @Test fun rejectsInconsistentOrDuplicateSourceChains() {
        val first=entry();assertFailsWith<IllegalArgumentException> { ItemRecoveryCheck(record(listOf(first,first))) }
        assertFailsWith<IllegalArgumentException> { ItemRecoveryCheck(record(listOf(entry(),entry()))) }
    }
    @Test fun cyclicHistoryStaysIndistinguishable() {
        val check=ItemRecoveryCheck(record(listOf(entry(right,left),entry())))
        check.accept(left.owner,InventorySnapshot(mapOf(left to item)));check.accept(right.owner,InventorySnapshot(mapOf(right to ItemStackSnapshot.EMPTY)))
        assertEquals(ItemRecoveryObservation.BOTH,check.observe())
    }
    @Test fun rejectsTransientOwnershipAndNonconservingPlans() {
        val cursor=ItemSlotAddress(ItemSlotOwner.Cursor(UUID.randomUUID()),0)
        assertFailsWith<IllegalArgumentException> { ItemRecoveryCheck(record(listOf(entry(left,cursor)))) }
        val destructive=ItemRollbackEntry(UUID.randomUUID(),listOf(ItemSlotChange(left,item,ItemStackSnapshot.EMPTY)))
        assertFailsWith<IllegalArgumentException> { ItemRecoveryCheck(record(listOf(destructive))) }
    }
    @Test fun enforcesRecordOwnerAndSourcePayloadBudgets() {
        assertFailsWith<IllegalArgumentException> { ItemRecoveryCheck(record(emptyList())) }
        assertFailsWith<IllegalArgumentException> { ItemRecoveryCheck(record(List(51) { entry(left.copy(index=it*2),left.copy(index=it*2+1)) })) }
        val manyOwners=(1..33).flatMap { n ->
            val source=ItemSlotAddress(ItemSlotOwner.BlockContainer(dimension,BlockPosition(n,64,1)),0)
            listOf(ItemSlotChange(source,item,ItemStackSnapshot.EMPTY),ItemSlotChange(source.copy(index=1),ItemStackSnapshot.EMPTY,item))
        }
        assertFailsWith<IllegalArgumentException> { ItemRecoveryCheck(record(listOf(ItemRollbackEntry(UUID.randomUUID(),manyOwners)))) }
        val large=item.copy(itemData=BinaryPayload.of(ByteArray(1024*1024)))
        val payload=(0..8).flatMap { n -> listOf(ItemSlotChange(left.copy(index=n*2),large,ItemStackSnapshot.EMPTY),ItemSlotChange(left.copy(index=n*2+1),ItemStackSnapshot.EMPTY,large)) }
        assertFailsWith<IllegalArgumentException> { ItemRecoveryCheck(record(listOf(ItemRollbackEntry(UUID.randomUUID(),payload)))) }
    }
    @Test fun observedPayloadOverBudgetIsUnavailableInsteadOfRetained() {
        val entries=(0..8).map { entry(left.copy(index=it*2),left.copy(index=it*2+1)) }
        val check=ItemRecoveryCheck(record(entries));val large=item.copy(itemData=BinaryPayload.of(ByteArray(2*1024*1024)))
        check.accept(left.owner,InventorySnapshot(check.addresses(left.owner).associateWith { large }))
        assertEquals(ItemRecoveryObservation.UNAVAILABLE,check.observe())
    }
}
