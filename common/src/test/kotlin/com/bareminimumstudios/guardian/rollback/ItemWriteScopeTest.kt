package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ItemWriteScopeTest {
    private val dimension = ResourceId.parse("minecraft:overworld")
    private val first = ItemSlotOwner.BlockContainer(dimension, BlockPosition(1, 64, 1))
    private val second = ItemSlotOwner.PlayerInventory(UUID.randomUUID())
    private val unrelated = ItemSlotOwner.BlockContainer(dimension, BlockPosition(2, 64, 1))

    @Test fun authorizesOnlyOneReservedOwnerAndPreservesOtherOperations() {
        val gate = ItemOwnerCoordination()
        val lease = assertNotNull(gate.acquire(UUID.randomUUID(), listOf(first, second)))
        val other = assertNotNull(gate.acquire(UUID.randomUUID(), listOf(unrelated)))
        val scope = ItemWriteScope(gate)
        assertEquals(42, scope.write(lease, first) { permit ->
            permit.requireCurrent(first)
            assertFailsWith<IllegalStateException> { permit.requireCurrent(second) }
            assertFalse(gate.allowsMutation(first))
            42
        })
        assertTrue(lease.isCurrent(lease.owners))
        assertTrue(other.isCurrent(other.owners))
    }

    @Test fun escapedPermitCannotAuthorizeAfterReturnOrDuringAnotherScope() {
        val gate = ItemOwnerCoordination()
        val lease = assertNotNull(gate.acquire(UUID.randomUUID(), listOf(first)))
        val scope = ItemWriteScope(gate)
        val escaped = scope.write(lease, first) { it }
        assertFailsWith<IllegalStateException> { escaped.requireCurrent(first) }
        scope.write(lease, first) { current ->
            assertFailsWith<IllegalStateException> { escaped.requireCurrent(first) }
            current.requireCurrent(first)
        }
        assertTrue(lease.isCurrent(lease.owners))
    }

    @Test fun foreignRegistryAndUnreservedOwnerCannotRunCallbacks() {
        val gate = ItemOwnerCoordination()
        val foreign = ItemOwnerCoordination()
        val id = UUID.randomUUID()
        val lease = assertNotNull(gate.acquire(id, listOf(first)))
        val other = assertNotNull(foreign.acquire(id, listOf(first)))
        val scope = ItemWriteScope(gate)
        var calls = 0
        assertFailsWith<IllegalStateException> { scope.write(other, first) { calls++ } }
        assertFailsWith<IllegalStateException> { scope.write(lease, second) { calls++ } }
        assertEquals(0, calls)
        assertTrue(lease.isCurrent(lease.owners))
        assertTrue(other.isCurrent(other.owners))
    }

    @Test fun releasedLeaseCannotOpenScope() {
        val gate = ItemOwnerCoordination()
        val lease = assertNotNull(gate.acquire(UUID.randomUUID(), listOf(first)))
        lease.close()
        assertFailsWith<IllegalStateException> { ItemWriteScope(gate).write(lease, first) { error("Must not run") } }
    }

    @Test fun nestedScopeIsRefusedWithoutReplacingOuterPermit() {
        val gate = ItemOwnerCoordination()
        val lease = assertNotNull(gate.acquire(UUID.randomUUID(), listOf(first, second)))
        val scope = ItemWriteScope(gate)
        scope.write(lease, first) { permit ->
            assertFailsWith<IllegalStateException> { scope.write(lease, second) { error("Must not run") } }
            permit.requireCurrent(first)
        }
        scope.write(lease, second) { it.requireCurrent(second) }
    }

    @Test fun callbackFailureRevokesWholeOperationAndClearsScope() {
        val gate = ItemOwnerCoordination()
        val lease = assertNotNull(gate.acquire(UUID.randomUUID(), listOf(first, second)))
        val other = assertNotNull(gate.acquire(UUID.randomUUID(), listOf(unrelated)))
        val scope = ItemWriteScope(gate)
        val failure = IllegalArgumentException("fixture")
        var escaped: ItemWriteScope.Permit? = null
        assertSame(failure, assertFailsWith<IllegalArgumentException> {
            scope.write(lease, first) { escaped = it; throw failure }
        })
        assertEquals(ItemOwnerLeaseState.INVALIDATED, lease.state)
        assertTrue(other.isCurrent(other.owners))
        assertFailsWith<IllegalStateException> { escaped!!.requireCurrent(first) }
        val replacement = assertNotNull(gate.acquire(lease.operationId, lease.owners))
        scope.write(replacement, first) { it.requireCurrent(first) }
    }

    @Test fun staleScopeCannotReturnSuccessOrRevokeReplacement() {
        val gate = ItemOwnerCoordination()
        val lease = assertNotNull(gate.acquire(UUID.randomUUID(), listOf(first)))
        val scope = ItemWriteScope(gate)
        var replacement: ItemOwnerCoordination.Lease? = null
        assertFailsWith<IllegalStateException> {
            scope.write(lease, first) {
                gate.invalidate(first)
                replacement = assertNotNull(gate.acquire(lease.operationId, lease.owners))
                "invalid success"
            }
        }
        val currentReplacement = assertNotNull(replacement)
        assertTrue(currentReplacement.isCurrent(currentReplacement.owners))
        scope.write(currentReplacement, first) { it.requireCurrent(first) }
    }

    @Test fun expiredScopeRejectsFurtherWritesAndReturn() {
        var now = 0L
        val gate = ItemOwnerCoordination { now }
        val lease = assertNotNull(gate.acquire(UUID.randomUUID(), listOf(first)))
        val scope = ItemWriteScope(gate)
        assertFailsWith<IllegalStateException> {
            scope.write(lease, first) { permit ->
                now = TimeUnit.SECONDS.toNanos(10)
                assertFailsWith<IllegalStateException> { permit.requireCurrent(first) }
            }
        }
        assertEquals(ItemOwnerLeaseState.EXPIRED, lease.state)
    }

    @Test fun stopRevokesActiveScopeWithoutReturningSuccess() {
        val gate = ItemOwnerCoordination()
        val lease = assertNotNull(gate.acquire(UUID.randomUUID(), listOf(first)))
        assertFailsWith<IllegalStateException> {
            ItemWriteScope(gate).write(lease, first) { gate.stop() }
        }
        assertEquals(ItemOwnerLeaseState.STOPPED, lease.state)
    }

    @Test fun wrongThreadCannotOpenOrUsePermit() {
        val gate = ItemOwnerCoordination()
        val lease = assertNotNull(gate.acquire(UUID.randomUUID(), listOf(first)))
        val scope = ItemWriteScope(gate)
        scope.write(lease, first) { permit ->
            CompletableFuture.runAsync {
                assertFailsWith<IllegalStateException> { permit.requireCurrent(first) }
                assertFailsWith<IllegalStateException> { scope.write(lease, first) { error("Must not run") } }
            }.get(5, TimeUnit.SECONDS)
            permit.requireCurrent(first)
        }
        assertTrue(lease.isCurrent(lease.owners))
    }

    @Test fun deferredCallbackCannotReusePermitAfterScopeEnds() {
        val gate = ItemOwnerCoordination()
        val lease = assertNotNull(gate.acquire(UUID.randomUUID(), listOf(first)))
        val scope = ItemWriteScope(gate)
        val ready = CompletableFuture<Unit>()
        val deferred = scope.write(lease, first) { permit -> ready.thenApply { permit.requireCurrent(first) } }
        ready.complete(Unit)
        assertTrue(deferred.isCompletedExceptionally)
        assertTrue(lease.isCurrent(lease.owners))
    }
}
