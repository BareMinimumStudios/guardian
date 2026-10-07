package com.bareminimumstudios.guardian.domain

enum class ChangeCause(val storageCode: Int) {
    PLAYER(1),
    WORLD_EDIT(2),
    EXPLOSION(10),
    FIRE(11),
    FLUID(12),
    PISTON(13),
    ENTITY(20),
    WORLD_GENERATION(30),
    SYSTEM(40),
    UNKNOWN(127);

    companion object {
        private val byStorageCode = entries.associateBy(ChangeCause::storageCode)
        fun fromStorageCode(code: Int): ChangeCause =
            byStorageCode[code] ?: throw IllegalArgumentException("Unknown change-cause storage code: $code")
    }
}
