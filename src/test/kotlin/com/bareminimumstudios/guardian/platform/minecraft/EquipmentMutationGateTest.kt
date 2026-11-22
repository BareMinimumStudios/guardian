package com.bareminimumstudios.guardian.platform.minecraft

import java.util.concurrent.CompletableFuture
import kotlin.test.*

class EquipmentMutationGateTest {
    @Test fun equipmentHandoffsAndOutstandingReloadsExcludeEachOtherUntilEveryReloadDrains() {
        val root=Any();val registration=EquipmentMutationGate.acquire(listOf(root))
        try { assertFalse(EquipmentMutationGate.beginResourceReload()) } finally { registration.close() }
        assertTrue(EquipmentMutationGate.beginResourceReload())
        assertTrue(EquipmentMutationGate.beginResourceReload())
        try {
            assertFailsWith<IllegalStateException> { EquipmentMutationGate.acquire(listOf(root)) }
            EquipmentMutationGate.endResourceReload()
            assertFailsWith<IllegalStateException> { EquipmentMutationGate.acquire(listOf(root)) }
        } finally { EquipmentMutationGate.endResourceReload() }
        EquipmentMutationGate.acquire(listOf(root)).close()
    }

    @Test fun cancellingOrCompletingAReloadObserverCannotReleaseTheNativeReloadEarly() {
        val pending=CompletableFuture<Void>()
        val observed=EquipmentMutationGate.reloadResources { pending }
        assertFalse(observed.cancel(true));assertFalse(pending.isDone)
        // Even a caller forcibly completing its own observer cannot end exclusion.
        observed.complete(null)
        assertFailsWith<IllegalStateException> { EquipmentMutationGate.acquire(listOf(Any())) }
        pending.complete(null)
        EquipmentMutationGate.acquire(listOf(Any())).close()
    }
    @Test fun reloadRefusalDoesNotStartWorkAndNativeFailureReleasesOnlyItsOwnReload() {
        var started=false
        val registration=EquipmentMutationGate.acquire(listOf(Any()))
        try {
            assertTrue(EquipmentMutationGate.reloadResources { started=true;CompletableFuture.completedFuture(null) }.isCompletedExceptionally)
            assertFalse(started)
        } finally { registration.close() }
        assertTrue(EquipmentMutationGate.reloadResources { throw IllegalStateException("native start failed") }.isCompletedExceptionally)
        val pending=CompletableFuture<Void>();val observed=EquipmentMutationGate.reloadResources { pending }
        pending.completeExceptionally(IllegalStateException("native asynchronous reload failed"))
        assertTrue(observed.isCompletedExceptionally)
        EquipmentMutationGate.acquire(listOf(Any())).close()
    }

    @Test fun rootsUsePhysicalIdentityAndCannotBeAcquiredTwice() {
        val first=mutableListOf(1);val equal=mutableListOf(1)
        val registration=EquipmentMutationGate.acquire(listOf(first,first))
        try {
            assertTrue(EquipmentMutationGate.blocked(first));assertFalse(EquipmentMutationGate.blocked(equal))
            assertFailsWith<IllegalStateException> { EquipmentMutationGate.acquire(listOf(first)) }
            EquipmentMutationGate.acquire(listOf(equal)).close()
        } finally { registration.close() }
        assertFalse(EquipmentMutationGate.blocked(first))
    }

    @Test fun nativeReplacementPermitRequiresTheExactRootAndValueAndIsConsumedOnce() {
        val root=Any();val other=Any();val value=Any()
        val registration=EquipmentMutationGate.acquire(listOf(root,other))
        try {
            assertFalse(EquipmentMutationGate.allowsReplacement(root,value))
            EquipmentMutationGate.replace(root,value) {
                assertFalse(EquipmentMutationGate.allowsReplacement(other,value))
                assertFalse(EquipmentMutationGate.allowsReplacement(root,Any()))
                assertTrue(EquipmentMutationGate.allowsReplacement(root,value))
                assertFalse(EquipmentMutationGate.allowsReplacement(root,value))
            }
            assertFalse(EquipmentMutationGate.allowsReplacement(root,value))
        } finally { registration.close() }
        assertTrue(EquipmentMutationGate.allowsReplacement(root,value))
    }

    @Test fun callbackFailureAndUnconsumedWritesDoNotLeaveAmbientAuthority() {
        val root=Any();val value=Any();val registration=EquipmentMutationGate.acquire(listOf(root))
        try {
            assertFailsWith<IllegalStateException> { EquipmentMutationGate.replace(root,value) {} }
            assertFailsWith<IllegalArgumentException> {
                EquipmentMutationGate.replace(root,value) {
                    assertTrue(EquipmentMutationGate.allowsReplacement(root,value));throw IllegalArgumentException("native callback failed")
                }
            }
            assertFalse(EquipmentMutationGate.allowsReplacement(root,value))
        } finally { registration.close() }
    }

    @Test fun anotherThreadCannotConsumeAPermitOrEndItsNativeRootOwnership() {
        val root=Any();val value=Any();val registration=EquipmentMutationGate.acquire(listOf(root))
        try {
            EquipmentMutationGate.replace(root,value) {
                CompletableFuture.runAsync {
                    assertTrue(EquipmentMutationGate.blocked(root))
                    assertFalse(EquipmentMutationGate.allowsReplacement(root,value))
                    assertFailsWith<IllegalStateException> { registration.close() }
                }.join()
                assertTrue(EquipmentMutationGate.allowsReplacement(root,value))
            }
        } finally { registration.close() }
    }
}
