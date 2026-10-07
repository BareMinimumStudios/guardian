package com.bareminimumstudios.guardian.logging.container

import java.util.UUID
import kotlin.test.*

class ActionCaptureScopeTest {
    @Test fun nestedSamePlayerIsCoveredByOuterLease() {
        val scope = ActionCaptureScope(); val player = UUID.randomUUID()
        assertNotNull(scope.enter(player)).use { assertNull(scope.enter(player)) }
        assertNotNull(scope.enter(player)).close()
    }
    @Test fun differentPlayersCanNestWithoutSharingAnAction() {
        val scope = ActionCaptureScope()
        assertNotNull(scope.enter(UUID.randomUUID())).use { assertNotNull(scope.enter(UUID.randomUUID())).use {} }
    }
    @Test fun exceptionReleasesScope() {
        val scope = ActionCaptureScope(); val player = UUID.randomUUID()
        assertFailsWith<IllegalStateException> { assertNotNull(scope.enter(player)).use { error("Operation failed") } }
        assertNotNull(scope.enter(player)).close()
    }
    @Test fun duplicateCloseDoesNotReleaseANewerLease() {
        val scope = ActionCaptureScope(); val player = UUID.randomUUID()
        val first = assertNotNull(scope.enter(player)); first.close()
        assertNotNull(scope.enter(player)).use { first.close(); assertNull(scope.enter(player)) }
    }
    @Test fun crossThreadCloseCannotCorruptOwners() {
        val scope = ActionCaptureScope(); val player = UUID.randomUUID(); val lease = assertNotNull(scope.enter(player))
        var failure: Throwable? = null
        val thread = Thread { try { lease.close() } catch (error: Throwable) { failure = error } }
        thread.start(); thread.join()
        assertIs<IllegalStateException>(failure); assertNull(scope.enter(player)); lease.close()
        assertNotNull(scope.enter(player)).close()
    }
}
