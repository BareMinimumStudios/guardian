package com.bareminimumstudios.guardian.domain

enum class ActionType(val storageCode: Int) {
    BLOCK_PLACE(1),
    BLOCK_BREAK(2),
    BLOCK_CHANGE(3),
    CONTAINER_CHANGE(10),
    ITEM_CHANGE(20),
    ENTITY_SPAWN(30),
    ENTITY_REMOVE(31),
    PLAYER_SESSION(40),
    CHAT(50),
    COMMAND(51);

    companion object {
        private val byStorageCode = entries.associateBy(ActionType::storageCode)
        fun fromStorageCode(code: Int): ActionType =
            byStorageCode[code] ?: throw IllegalArgumentException("Unknown action storage code: $code")
    }
}
