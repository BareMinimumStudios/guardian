package com.bareminimumstudios.guardian.domain

enum class ActionType(val storageCode: Int) {
    BLOCK_PLACE(1),
    BLOCK_BREAK(2),
    BLOCK_CHANGE(3),
    CONTAINER_CHANGE(10),
    ITEM_CHANGE(20),
    ENTITY_SPAWN(30),
    ENTITY_REMOVE(31),
    ENTITY_DEATH(32),
    ENTITY_NAME_CHANGE(33),
    ENTITY_TAME(34),
    ENTITY_BREED(35),
    FLUID_PLACE(36),
    FLUID_REMOVE(37),
    PLAYER_SESSION(40),
    CHAT(50),
    COMMAND(51),
    FRAME_PLACE(60),
    FRAME_REMOVE(61),
    FRAME_CHANGE(62);

    companion object {
        private val byStorageCode = entries.associateBy(ActionType::storageCode)
        fun fromStorageCode(code: Int): ActionType =
            byStorageCode[code] ?: throw IllegalArgumentException("Unknown action storage code: $code")
    }
}
