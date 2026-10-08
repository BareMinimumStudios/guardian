package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ItemChunkInvalidationTest {
    private val dimension = ResourceId.parse("minecraft:overworld")
    private fun block(x: Int, z: Int, world: ResourceId = dimension) =
        ItemSlotOwner.BlockContainer(world, BlockPosition(x, 64, z))
    private fun reserve(owners: ItemOwnerCoordination, vararg keys: ItemSlotOwner) =
        assertNotNull(owners.acquire(UUID.randomUUID(), keys.toList()))

    @Test fun chunkRemovalInvalidatesWholeOperationsIncludingPlayerOwners() {
        val owners = ItemOwnerCoordination()
        val player = ItemSlotOwner.PlayerInventory(UUID.randomUUID())
        val affected = reserve(owners, block(15, 15), block(16, 16), player)
        val unrelated = reserve(owners, block(32, 32))
        owners.invalidateBlockChunk(dimension, 0, 0)
        assertEquals(ItemOwnerLeaseState.INVALIDATED, affected.state)
        assertEquals(ItemOwnerLeaseState.ACTIVE, unrelated.state)
        assertTrue(owners.allowsMutation(player))
        assertTrue(owners.allowsMutation(block(16, 16)))
    }

    @Test fun negativeCoordinatesAndDimensionsUseExactChunkOwnership() {
        val owners = ItemOwnerCoordination()
        val affected = reserve(owners, block(-1, -16))
        val adjacent = reserve(owners, block(-17, -16))
        val otherWorld = reserve(owners, block(-1, -16, ResourceId.parse("minecraft:the_nether")))
        owners.invalidateBlockChunk(dimension, -1, -1)
        assertEquals(ItemOwnerLeaseState.INVALIDATED, affected.state)
        assertEquals(ItemOwnerLeaseState.ACTIVE, adjacent.state)
        assertEquals(ItemOwnerLeaseState.ACTIVE, otherWorld.state)
    }

    @Test fun staleCloseCannotReleaseReplacementAfterChunkInvalidation() {
        val owners = ItemOwnerCoordination()
        val operation = UUID.randomUUID()
        val key = block(0, 0)
        val stale = assertNotNull(owners.acquire(operation, listOf(key)))
        owners.invalidateBlockChunk(dimension, 0, 0)
        val replacement = assertNotNull(owners.acquire(operation, listOf(key)))
        stale.close()
        assertTrue(replacement.isCurrent(setOf(key)))
        assertFalse(owners.allowsMutation(key, stale))
    }

    @Test fun chunkRemovalPreservesExpiryAndShutdownStates() {
        var now = 0L
        val owners = ItemOwnerCoordination { now }
        val expired = reserve(owners, block(0, 0))
        now = TimeUnit.SECONDS.toNanos(10)
        owners.invalidateBlockChunk(dimension, 0, 0)
        assertEquals(ItemOwnerLeaseState.EXPIRED, expired.state)
        val stopped = reserve(owners, block(0, 0))
        owners.stop()
        owners.invalidateBlockChunk(dimension, 0, 0)
        assertEquals(ItemOwnerLeaseState.STOPPED, stopped.state)
    }

    @Test fun chunkRemovalRejectsAnotherThread() {
        val owners = ItemOwnerCoordination()
        CompletableFuture.runAsync {
            assertFailsWith<IllegalStateException> { owners.invalidateBlockChunk(dimension, 0, 0) }
        }.get(5, TimeUnit.SECONDS)
    }
}
