package com.bareminimumstudios.guardian.checkpoint

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.ItemOwnerCoordination
import java.util.UUID
import kotlin.test.*

class EquipmentOwnerReservationTest {
    @Test fun equipmentOwnershipExcludesOrdinaryPlayerMutationsAndCompetingLayouts() {
        val id=UUID.randomUUID();val plain=ItemSlotOwner.PlayerInventory(id)
        val layout=PlayerStorageLayout(41,setOf("trinkets"),listOf(EquipmentSlotAddress("trinkets","hand/ring/0")))
        val extended=ItemSlotOwner.PlayerInventory(id,layout);val owners=ItemOwnerCoordination()
        val lease=assertNotNull(owners.acquire(UUID.randomUUID(),listOf(extended)))
        assertFalse(owners.allowsMutation(plain));assertFalse(owners.allowsMutation(extended))
        assertTrue(owners.allowsMutation(plain,lease));assertTrue(owners.allowsMutation(extended,lease))
        val changed=ItemSlotOwner.PlayerInventory(id,PlayerStorageLayout(41,setOf("trinkets"),listOf(EquipmentSlotAddress("trinkets","head/hat/0"))))
        assertFalse(owners.allowsMutation(changed,lease))
        assertNull(owners.acquire(UUID.randomUUID(),listOf(plain)))
        assertNull(owners.acquire(UUID.randomUUID(),listOf(changed)))
        owners.invalidate(plain);assertFalse(lease.isCurrent(setOf(extended)))
    }
    @Test fun duplicatePlayerAliasesCannotAcquireOneLeaseAndReleasedAliasesDoNotBlock() {
        val id=UUID.randomUUID();val plain=ItemSlotOwner.PlayerInventory(id)
        val extended=ItemSlotOwner.PlayerInventory(id,PlayerStorageLayout(41,emptySet(),emptyList()))
        val owners=ItemOwnerCoordination()
        assertNull(owners.acquire(UUID.randomUUID(),listOf(plain,extended)))
        val lease=assertNotNull(owners.acquire(UUID.randomUUID(),listOf(extended)))
        lease.close();assertTrue(owners.allowsMutation(plain));assertNotNull(owners.acquire(UUID.randomUUID(),listOf(plain)))
    }
    @Test fun equipmentTopologyCannotBeChangedThroughCallersCollections() {
        val providers=mutableSetOf("curios");val slots=mutableListOf(EquipmentSlotAddress("curios","ring/0"))
        val layout=PlayerStorageLayout(41,providers,slots);providers.clear();slots.clear()
        assertEquals(42,layout.slotCount);assertEquals(setOf("curios"),layout.providers)
        assertFailsWith<UnsupportedOperationException> { (layout.extraSlots as MutableList).clear() }
    }
}
