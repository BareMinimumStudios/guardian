package com.bareminimumstudios.guardian.domain

/** Provider-owned names, never a menu index or a guess based on current slot order. */
data class EquipmentSlotAddress(val provider: String, val slot: String) {
    init {
        require(provider.matches(Regex("[a-z0-9_.-]{1,64}"))) { "Invalid equipment provider" }
        require(slot.length in 1..128 && slot.none { it.isISOControl() }) { "Invalid equipment slot" }
    }
}
