package com.bareminimumstudios.guardian.checkpoint

import com.bareminimumstudios.guardian.domain.*
import kotlin.test.*

class PlayerEquipmentSnapshotTest {
    @Test fun imagesDefensivelyRetainProviderTopologyAndEmptySlots() {
        val providers=linkedMapOf("trinkets" to "3.10.0", "curios" to "9")
        val address=EquipmentSlotAddress("trinkets","hand/ring/0")
        val slots=linkedMapOf(address to ItemStackSnapshot.EMPTY)
        val saved=PlayerEquipmentSnapshot(providers,slots)
        providers.clear();slots.clear()
        assertEquals(setOf("trinkets","curios"),saved.providers.keys)
        assertEquals(ItemStackSnapshot.EMPTY,saved.slots[address])
        assertFailsWith<UnsupportedOperationException> { (saved.slots as MutableMap).clear() }
    }
    @Test fun providerFailuresCannotBecomeUnattributedOrOversizedImages() {
        val address=EquipmentSlotAddress("accessories","cosmetic/ring/0")
        assertFailsWith<IllegalArgumentException> { PlayerEquipmentSnapshot(emptyMap(),mapOf(address to ItemStackSnapshot.EMPTY)) }
        assertFailsWith<IllegalArgumentException> { EquipmentSlotAddress("accessories","ring/\n0") }
        assertFailsWith<IllegalArgumentException> { PlayerEquipmentSnapshot(mapOf("accessories" to "1"),(0..256).associate { EquipmentSlotAddress("accessories","ring/$it") to ItemStackSnapshot.EMPTY }) }
    }
    @Test fun cosmeticSlotsAndNestedItemComponentsRemainDistinctAndImmutable() {
        val bytes=byteArrayOf(1,2,3)
        val item=ItemStackSnapshot(ResourceId.parse("travelersbackpack:standard"),1,BinaryPayload.of(bytes))
        val slots=mapOf(EquipmentSlotAddress("accessories","back/0") to item,EquipmentSlotAddress("accessories","cosmetic/back/0") to ItemStackSnapshot.EMPTY)
        val saved=PlayerEquipmentSnapshot(mapOf("accessories" to "1"),slots)
        bytes[0]=9
        assertEquals(2,saved.slots.size)
        assertContentEquals(byteArrayOf(1,2,3),saved.slots.values.first { !it.isEmpty }.itemData!!.copyBytes())
    }
}
