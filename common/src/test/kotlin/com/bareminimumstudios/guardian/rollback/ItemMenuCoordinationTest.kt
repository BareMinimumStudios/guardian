package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ItemMenuCoordinationTest {
    private val player = UUID.randomUUID()
    private val inventory = ItemSlotOwner.PlayerInventory(player)
    private fun block(x: Int) = ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"), BlockPosition(x, 64, 0))
    private fun reserve(owners: ItemOwnerCoordination, vararg keys: ItemSlotOwner) =
        assertNotNull(owners.acquire(UUID.randomUUID(), keys.toList()))

    @Test fun idlePathsDoNotResolveMenus() {
        val guard = ItemMenuCoordination(ItemOwnerCoordination())
        assertTrue(guard.allowsMutation(player) { error("Must not resolve") })
        guard.beforeCleanup(player) { error("Must not resolve") }
    }

    @Test fun actorReservationRejectsWithoutResolvingMenu() {
        val owners = ItemOwnerCoordination()
        reserve(owners, inventory)
        assertFalse(ItemMenuCoordination(owners).allowsMutation(player) { error("Must not resolve") })
    }

    @Test fun everyParticipatingOwnerIsChecked() {
        for (key in listOf(block(1), block(2), ItemSlotOwner.PlayerInventory(UUID.randomUUID()))) {
            val owners = ItemOwnerCoordination()
            reserve(owners, key)
            assertFalse(ItemMenuCoordination(owners).allowsMutation(player) { listOf(block(1), block(2), key) })
        }
    }

    @Test fun unrelatedAndPlayerOnlyMenusRemainAllowed() {
        val owners = ItemOwnerCoordination()
        reserve(owners, block(1))
        val guard = ItemMenuCoordination(owners)
        assertTrue(guard.allowsMutation(player) { listOf(block(2), inventory) })
        assertTrue(guard.allowsMutation(player) { emptyList() })
    }

    @Test fun unknownInvalidOversizedOrFailedMenusRefuseMutation() {
        val owners = ItemOwnerCoordination()
        reserve(owners, block(1))
        val guard = ItemMenuCoordination(owners)
        assertFalse(guard.allowsMutation(player) { null })
        assertFalse(guard.allowsMutation(player) { error("Unavailable") })
        assertFalse(guard.allowsMutation(player) { List(33) { block(2) } })
        assertFalse(guard.allowsMutation(player) { listOf(ItemSlotOwner.Cursor(player)) })
        assertFalse(guard.allowsMutation(player) { listOf(ItemSlotOwner.CraftingGrid(player, 0)) })
    }

    @Test fun duplicatesWithinBoundAreSafe() {
        val owners = ItemOwnerCoordination()
        reserve(owners, block(1))
        val guard = ItemMenuCoordination(owners)
        assertTrue(guard.allowsMutation(player) { List(32) { block(2) } })
        assertFalse(guard.allowsMutation(player) { List(32) { block(1) } })
    }

    @Test fun cleanupInvalidatesWholeActorOperationBeforeReturningItems() {
        val owners = ItemOwnerCoordination()
        val lease = reserve(owners, inventory, block(1))
        ItemMenuCoordination(owners).beforeCleanup(player) { error("No remaining reservations") }
        assertEquals(ItemOwnerLeaseState.INVALIDATED, lease.state)
        assertTrue(owners.allowsMutation(block(1)))
    }

    @Test fun knownCleanupInvalidatesAffectedOperationsAndPreservesUnrelatedOnes() {
        val owners = ItemOwnerCoordination()
        val affected = reserve(owners, block(1), block(2))
        val unrelated = reserve(owners, block(3))
        ItemMenuCoordination(owners).beforeCleanup(player) { listOf(block(2), inventory) }
        assertEquals(ItemOwnerLeaseState.INVALIDATED, affected.state)
        assertEquals(ItemOwnerLeaseState.ACTIVE, unrelated.state)
    }

    @Test fun unknownCleanupInvalidatesAllRemainingOperations() {
        for (resolve in listOf<() -> Collection<ItemSlotOwner>?>(
            { null }, { error("Unavailable") }, { List(33) { block(9) } }, { listOf(ItemSlotOwner.Cursor(player)) }
        )) {
            val owners = ItemOwnerCoordination()
            val first = reserve(owners, block(1))
            val second = reserve(owners, block(2))
            ItemMenuCoordination(owners).beforeCleanup(player, resolve)
            assertEquals(ItemOwnerLeaseState.INVALIDATED, first.state)
            assertEquals(ItemOwnerLeaseState.INVALIDATED, second.state)
        }
    }

    @Test fun expiryAndReleaseRestoreOrdinaryMenuAccess() {
        var now = 0L
        val owners = ItemOwnerCoordination { now }
        val guard = ItemMenuCoordination(owners)
        val first = reserve(owners, block(1))
        now = TimeUnit.SECONDS.toNanos(10)
        assertTrue(guard.allowsMutation(player) { error("Expired") })
        assertEquals(ItemOwnerLeaseState.EXPIRED, first.state)
        reserve(owners, block(1)).close()
        assertTrue(guard.allowsMutation(player) { error("Released") })
    }

    @Test fun stoppedPolicyRefusesMutationsAndLeavesCleanupAvailable() {
        val owners = ItemOwnerCoordination()
        val lease = reserve(owners, inventory)
        owners.stop()
        val guard = ItemMenuCoordination(owners)
        assertFalse(guard.allowsMutation(player) { error("Stopped") })
        guard.beforeCleanup(player) { error("Stopped") }
        assertEquals(ItemOwnerLeaseState.STOPPED, lease.state)
    }

    @Test fun policyRejectsForeignThreads() {
        val guard = ItemMenuCoordination(ItemOwnerCoordination())
        CompletableFuture.runAsync {
            assertFailsWith<IllegalStateException> { guard.allowsMutation(player) { emptyList() } }
            assertFailsWith<IllegalStateException> { guard.beforeCleanup(player) { emptyList() } }
        }.get(5, TimeUnit.SECONDS)
    }
}
