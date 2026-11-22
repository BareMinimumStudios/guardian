package com.bareminimumstudios.guardian.domain

import java.util.Collections

/** A saved topology, not permission to resolve or mutate any native inventory. */
class PlayerStorageLayout(val normalSlots:Int,providers:Set<String>,extraSlots:Collection<EquipmentSlotAddress>) {
    val providers:Set<String> = Collections.unmodifiableSet(providers.toSortedSet())
    val extraSlots:List<EquipmentSlotAddress> = Collections.unmodifiableList(extraSlots.sortedWith(
        compareBy<EquipmentSlotAddress> { it.provider }.thenBy { it.slot }))
    val slotCount:Int get() = normalSlots+extraSlots.size
    init {
        require(normalSlots in setOf(41,43) && slotCount<=256 && this.providers.size<=16)
        require(this.extraSlots.distinct().size==this.extraSlots.size)
        this.providers.forEach { EquipmentSlotAddress(it,"validation") }
        require(this.extraSlots.all { it.provider in this.providers })
    }
    override fun equals(other:Any?) = other is PlayerStorageLayout && normalSlots==other.normalSlots && providers==other.providers && extraSlots==other.extraSlots
    override fun hashCode() = (31*normalSlots+providers.hashCode())*31+extraSlots.hashCode()
    override fun toString() = "PlayerStorageLayout(normal=$normalSlots, providers=$providers, slots=$extraSlots)"
}
