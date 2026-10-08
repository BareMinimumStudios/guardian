package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ItemOwnerCoordinationTest {
    private val dimension = ResourceId.parse("minecraft:overworld")
    private fun block(x: Int) = ItemSlotOwner.BlockContainer(dimension, BlockPosition(x, 64, 1))
    private val player = ItemSlotOwner.PlayerInventory(UUID.randomUUID())

    @Test fun reservesAllOwnersAndRequiresTheActualPermit() {
        val gate = ItemOwnerCoordination()
        val owners = setOf(block(1), player)
        val lease = assertNotNull(gate.acquire(UUID.randomUUID(), owners))
        assertTrue(lease.isCurrent(owners))
        owners.forEach { assertFalse(gate.allowsMutation(it)); assertTrue(gate.allowsMutation(it, lease)) }
        assertTrue(gate.allowsMutation(block(2)))
        assertFalse(gate.allowsMutation(block(2), lease))
    }

    @Test fun overlapRefusalDoesNotPartiallyReserveOtherOwners() {
        val gate = ItemOwnerCoordination()
        val first = assertNotNull(gate.acquire(UUID.randomUUID(), listOf(block(1))))
        assertNull(gate.acquire(UUID.randomUUID(), listOf(block(2), block(1))))
        assertTrue(gate.allowsMutation(block(2)))
        assertTrue(first.isCurrent(setOf(block(1))))
    }

    @Test fun duplicateOperationCannotObtainAnotherReservation() {
        val gate = ItemOwnerCoordination()
        val id = UUID.randomUUID()
        assertNotNull(gate.acquire(id, listOf(block(1))))
        assertNull(gate.acquire(id, listOf(block(2))))
        assertTrue(gate.allowsMutation(block(2)))
    }

    @Test fun invalidOwnerSetsLeaveNoReservations() {
        val gate = ItemOwnerCoordination()
        val invalid = listOf(emptyList(), listOf(block(1), block(1)), (1..33).map(::block),
            listOf(block(1), ItemSlotOwner.Cursor(player.playerId)),
            listOf(block(1), ItemSlotOwner.CraftingGrid(player.playerId, 0)))
        invalid.forEach { assertNull(gate.acquire(UUID.randomUUID(), it)) }
        assertTrue(gate.allowsMutation(block(1)))
    }

    @Test fun supportsThirtyTwoOwnersAndThirtyTwoIndependentOperations() {
        val gate = ItemOwnerCoordination()
        val large = assertNotNull(gate.acquire(UUID.randomUUID(), (1..32).map(::block)))
        large.close()
        val leases = (1..32).map { assertNotNull(gate.acquire(UUID.randomUUID(), listOf(block(it)))) }
        assertNull(gate.acquire(UUID.randomUUID(), listOf(block(33))))
        assertTrue(gate.allowsMutation(block(33)))
        leases.first().close()
        assertNotNull(gate.acquire(UUID.randomUUID(), listOf(block(33))))
    }

    @Test fun inputAndPublishedOwnersCannotChangeTheReservation() {
        val gate = ItemOwnerCoordination()
        val input = mutableListOf(block(1), player)
        val lease = assertNotNull(gate.acquire(UUID.randomUUID(), input))
        input.clear()
        assertEquals(setOf(block(1), player), lease.owners)
        assertFailsWith<UnsupportedOperationException> { (lease.owners as MutableSet).clear() }
        assertFalse(gate.allowsMutation(block(1)))
    }

    @Test fun requiresExactOwnerSetForCurrentCheck() {
        val gate = ItemOwnerCoordination()
        val lease = assertNotNull(gate.acquire(UUID.randomUUID(), listOf(block(1), player)))
        assertFalse(lease.isCurrent(setOf(block(1))))
        assertFalse(lease.isCurrent(setOf(block(1), player, block(2))))
        assertTrue(lease.isCurrent(setOf(player, block(1))))
    }

    @Test fun invalidatingOneOwnerReleasesTheWholeOperation() {
        val gate = ItemOwnerCoordination()
        val lease = assertNotNull(gate.acquire(UUID.randomUUID(), listOf(block(1), player)))
        gate.invalidate(block(1))
        assertEquals(ItemOwnerLeaseState.INVALIDATED, lease.state)
        assertFalse(lease.isCurrent(lease.owners))
        assertTrue(gate.allowsMutation(player))
        assertFalse(gate.allowsMutation(player, lease))
        gate.invalidate(block(1)); lease.close()
        assertEquals(ItemOwnerLeaseState.INVALIDATED, lease.state)
    }

    @Test fun staleCloseAndPermitCannotAffectReplacementWithSameOperationId() {
        val gate = ItemOwnerCoordination()
        val id = UUID.randomUUID()
        val old = assertNotNull(gate.acquire(id, listOf(block(1))))
        old.close()
        val replacement = assertNotNull(gate.acquire(id, listOf(block(1))))
        old.close()
        assertEquals(ItemOwnerLeaseState.RELEASED, old.state)
        assertFalse(gate.allowsMutation(block(1), old))
        assertTrue(gate.allowsMutation(block(1), replacement))
    }

    @Test fun foreignRegistryPermitCannotBypassReservation() {
        val id = UUID.randomUUID()
        val gate = ItemOwnerCoordination()
        val own = assertNotNull(gate.acquire(id, listOf(block(1))))
        val foreign = assertNotNull(ItemOwnerCoordination().acquire(id, listOf(block(1))))
        assertFalse(gate.allowsMutation(block(1), foreign))
        assertFalse(gate.allowsMutation(block(2), foreign))
        assertTrue(gate.allowsMutation(block(1), own))
    }

    @Test fun expiresAtDeadlineAndOldPermitCannotFallBackToOrdinaryAccess() {
        var now = 0L
        val gate = ItemOwnerCoordination { now }
        val lease = assertNotNull(gate.acquire(UUID.randomUUID(), listOf(block(1), player)))
        now = TimeUnit.SECONDS.toNanos(10) - 1
        assertTrue(lease.isCurrent(lease.owners))
        now++
        assertFalse(gate.allowsMutation(block(1), lease))
        assertEquals(ItemOwnerLeaseState.EXPIRED, lease.state)
        assertTrue(gate.allowsMutation(player))
        assertNotNull(gate.acquire(UUID.randomUUID(), listOf(block(1), player)))
    }

    @Test fun monotonicDeadlineHandlesNanoTimeWraparound() {
        var now = Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(5)
        val gate = ItemOwnerCoordination { now }
        val lease = assertNotNull(gate.acquire(UUID.randomUUID(), listOf(block(1))))
        now += TimeUnit.SECONDS.toNanos(10)
        assertEquals(ItemOwnerLeaseState.EXPIRED, lease.state)
    }

    @Test fun shutdownInvalidatesEveryLeaseAndRefusesNewWork() {
        val gate = ItemOwnerCoordination()
        val a = assertNotNull(gate.acquire(UUID.randomUUID(), listOf(block(1))))
        val b = assertNotNull(gate.acquire(UUID.randomUUID(), listOf(player)))
        gate.stop(); gate.stop(); a.close()
        assertEquals(ItemOwnerLeaseState.STOPPED, a.state)
        assertEquals(ItemOwnerLeaseState.STOPPED, b.state)
        assertNull(gate.acquire(UUID.randomUUID(), listOf(block(2))))
        assertFalse(gate.allowsMutation(block(1)))
        assertFalse(gate.allowsMutation(block(2)))
    }

    @Test fun wrongThreadCannotAcquirePermitInspectInvalidateOrRelease() {
        val gate = ItemOwnerCoordination()
        val lease = assertNotNull(gate.acquire(UUID.randomUUID(), listOf(block(1))))
        CompletableFuture.runAsync {
            assertFailsWith<IllegalStateException> { gate.acquire(UUID.randomUUID(), listOf(player)) }
            assertFailsWith<IllegalStateException> { gate.allowsMutation(block(1)) }
            assertFailsWith<IllegalStateException> { gate.invalidate(block(1)) }
            assertFailsWith<IllegalStateException> { gate.stop() }
            assertFailsWith<IllegalStateException> { lease.state }
            assertFailsWith<IllegalStateException> { lease.isCurrent(lease.owners) }
            assertFailsWith<IllegalStateException> { lease.close() }
        }.get(5, TimeUnit.SECONDS)
        assertTrue(lease.isCurrent(lease.owners))
    }

    @Test fun invalidatedLeaseStopsSaveCompletionWithoutAdvancingJournal() {
        val a = ItemSlotAddress(block(1), 0)
        val b = ItemSlotAddress(player, 0)
        val coal = ItemStackSnapshot(ResourceId.parse("minecraft:coal"), 1, BinaryPayload.of(byteArrayOf(1)))
        val record = ItemRollbackRecord(UUID.randomUUID(), 1, ItemRollbackPhase.APPLYING,
            listOf(ItemRollbackEntry(UUID.randomUUID(), listOf(
                ItemSlotChange(a, coal, ItemStackSnapshot.EMPTY),
                ItemSlotChange(b, ItemStackSnapshot.EMPTY, coal)))))
        var transitions = 0
        val journal = object : ItemRollbackJournal {
            override fun prepareItemRollback(operationId: UUID, createdAt: Long, newestFirst: List<ContainerTransactionSnapshot>): ItemRollbackRecord = error("Unexpected prepare")
            override fun itemRollback(operationId: UUID) = record
            override fun unfinishedItemRollbacks(limit: Int) = emptyList<ItemRollbackSummary>()
            override fun transitionItemRollback(operationId: UUID, expected: ItemRollbackPhase, next: ItemRollbackPhase): Boolean { transitions++; return true }
        }
        val gate = ItemOwnerCoordination()
        val lease = assertNotNull(gate.acquire(record.operationId, listOf(a.owner, b.owner)))
        val pending = CompletableFuture<InventorySnapshot?>()
        var saves = 0
        val port = object : ItemSavePort {
            // Test fixture only. A live port additionally needs platform exclusion and identity checks.
            override fun isExclusiveAndCurrent(owners: Set<ItemSlotOwner>) = lease.isCurrent(owners)
            override fun saveAndReadBack(owner: ItemSlotOwner, addresses: Set<ItemSlotAddress>): CompletableFuture<InventorySnapshot?> { saves++; return pending }
        }
        val driver = ItemSaveCompletion(record, journal, port)
        driver.advance()
        assertEquals(1, saves)
        gate.invalidate(b.owner)
        assertEquals(ItemSaveCompletionState.UNRESOLVED, driver.advance())
        val replacement = assertNotNull(gate.acquire(record.operationId, lease.owners))
        pending.complete(InventorySnapshot(mapOf(a to coal, b to ItemStackSnapshot.EMPTY)))
        driver.advance(); lease.close()
        assertEquals(1, saves)
        assertEquals(0, transitions)
        assertEquals(ItemRollbackPhase.APPLYING, journal.itemRollback(record.operationId).phase)
        assertTrue(replacement.isCurrent(replacement.owners))
    }
}
