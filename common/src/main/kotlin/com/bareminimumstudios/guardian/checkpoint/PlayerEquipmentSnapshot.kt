package com.bareminimumstudios.guardian.checkpoint

import com.bareminimumstudios.guardian.domain.ItemStackSnapshot
import com.bareminimumstudios.guardian.domain.EquipmentSlotAddress
import java.util.Collections

/** Complete topology, including empty slots and installed providers with no enabled slots. */
class PlayerEquipmentSnapshot(providers: Map<String,String>, slots: Map<EquipmentSlotAddress,ItemStackSnapshot>) {
    val providers: Map<String,String> = Collections.unmodifiableMap(providers.toSortedMap())
    val slots: Map<EquipmentSlotAddress,ItemStackSnapshot> = Collections.unmodifiableMap(
        slots.toSortedMap(compareBy<EquipmentSlotAddress> { it.provider }.thenBy { it.slot }))
    init {
        require(this.providers.size <= 16 && this.slots.size <= 256) { "Equipment topology exceeds budget" }
        this.providers.forEach { (id,version) ->
            EquipmentSlotAddress(id,"validation")
            require(version.length in 1..128 && version.none(Char::isISOControl)) { "Invalid equipment provider version" }
        }
        require(this.slots.keys.all { it.provider in this.providers }) { "Equipment slot has no recorded provider" }
        require(this.slots.values.all { it.isEmpty || it.itemData != null && it.itemData.size > 0 })
        require(this.slots.values.sumOf { (it.itemData?.size ?: 0).toLong() } <= InventoryCheckpoint.MAX_COMPONENT_BYTES)
    }
    override fun equals(other: Any?) = other is PlayerEquipmentSnapshot && providers == other.providers && slots == other.slots
    override fun hashCode() = 31 * providers.hashCode() + slots.hashCode()
}
