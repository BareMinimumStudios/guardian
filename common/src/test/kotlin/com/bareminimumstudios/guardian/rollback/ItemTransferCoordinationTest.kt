package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ItemTransferCoordinationTest {
    private fun block(x: Int, dimension: String = "minecraft:overworld") =
        ItemSlotOwner.BlockContainer(ResourceId.parse(dimension), BlockPosition(x, 64, 0))

    @Test fun idleGuardDoesNotResolveEndpointsOrAffectOrdinaryTransfers() {
        val guard = ItemTransferCoordination(ItemOwnerCoordination())
        assertTrue(guard.allowsExternalTransfer { error("Must not resolve without reservations") })
    }

    @Test fun anyReservedSourceDestinationOrChestHalfRefusesTransfer() {
        for (reserved in 1..4) {
            val owners = ItemOwnerCoordination()
            assertNotNull(owners.acquire(UUID.randomUUID(), listOf(block(reserved))))
            assertFalse(ItemTransferCoordination(owners).allowsExternalTransfer { (1..4).map(::block) })
        }
    }

    @Test fun unrelatedTransfersAndDifferentDimensionsRemainAllowed() {
        val owners = ItemOwnerCoordination()
        assertNotNull(owners.acquire(UUID.randomUUID(), listOf(block(1))))
        val guard = ItemTransferCoordination(owners)
        assertTrue(guard.allowsExternalTransfer { listOf(block(2), block(3)) })
        assertTrue(guard.allowsExternalTransfer { listOf(block(1, "minecraft:the_nether")) })
    }

    @Test fun unknownFailedOrUnsupportedEndpointResolutionRefusesWhileReserved() {
        val owners = ItemOwnerCoordination()
        assertNotNull(owners.acquire(UUID.randomUUID(), listOf(block(1))))
        val guard = ItemTransferCoordination(owners)
        assertFalse(guard.allowsExternalTransfer { null })
        assertFalse(guard.allowsExternalTransfer { emptyList() })
        assertFalse(guard.allowsExternalTransfer { error("Unavailable endpoint") })
        assertFalse(guard.allowsExternalTransfer { (2..6).map(::block) })
        assertFalse(guard.allowsExternalTransfer { listOf(ItemSlotOwner.PlayerInventory(UUID.randomUUID())) })
    }

    @Test fun duplicatePhysicalEndpointsAreCheckedOnce() {
        val owners = ItemOwnerCoordination()
        assertNotNull(owners.acquire(UUID.randomUUID(), listOf(block(1))))
        val guard = ItemTransferCoordination(owners)
        assertTrue(guard.allowsExternalTransfer { List(10) { block(2) } })
        assertFalse(guard.allowsExternalTransfer { List(10) { block(1) } })
    }

    @Test fun expiryResumesOrdinaryTransfersAndAvoidsEndpointResolution() {
        var now = 0L
        val owners = ItemOwnerCoordination { now }
        val lease = assertNotNull(owners.acquire(UUID.randomUUID(), listOf(block(1))))
        val guard = ItemTransferCoordination(owners)
        assertFalse(guard.allowsExternalTransfer { listOf(block(1), block(2)) })
        now = TimeUnit.SECONDS.toNanos(10)
        assertTrue(guard.allowsExternalTransfer { error("Expired leases must not resolve") })
        assertEquals(ItemOwnerLeaseState.EXPIRED, lease.state)
    }

    @Test fun closeAndInvalidationResumeTransfers() {
        for (invalidate in listOf(false, true)) {
            val owners = ItemOwnerCoordination()
            val lease = assertNotNull(owners.acquire(UUID.randomUUID(), listOf(block(1))))
            val guard = ItemTransferCoordination(owners)
            assertFalse(guard.allowsExternalTransfer { listOf(block(1)) })
            if (invalidate) owners.invalidate(block(1)) else lease.close()
            assertTrue(guard.allowsExternalTransfer { listOf(block(1)) })
        }
    }

    @Test fun stoppedGuardRefusesLateWorkWithoutResolvingEndpoints() {
        val owners = ItemOwnerCoordination()
        val guard = ItemTransferCoordination(owners)
        owners.stop()
        assertFalse(guard.allowsExternalTransfer { error("Stopped guard must not resolve") })
    }

    @Test fun guardRejectsCallsFromAnotherThread() {
        val guard = ItemTransferCoordination(ItemOwnerCoordination())
        CompletableFuture.runAsync {
            assertFailsWith<IllegalStateException> { guard.allowsExternalTransfer { listOf(block(1)) } }
        }.get(5, TimeUnit.SECONDS)
    }
}
