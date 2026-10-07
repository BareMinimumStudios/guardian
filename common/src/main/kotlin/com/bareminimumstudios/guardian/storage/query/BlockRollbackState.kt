package com.bareminimumstudios.guardian.storage.query

enum class BlockRollbackState(val storageCode: Int) {
    ACTIVE(0),
    ROLLED_BACK(1),
    PENDING(2);

    companion object {
        private val byCode = entries.associateBy(BlockRollbackState::storageCode)
        fun fromStorageCode(code: Int): BlockRollbackState =
            byCode[code] ?: throw IllegalArgumentException("Unknown block rollback state: $code")
    }
}
