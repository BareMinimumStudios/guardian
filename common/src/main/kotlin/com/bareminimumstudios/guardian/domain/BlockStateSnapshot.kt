package com.bareminimumstudios.guardian.domain

/**
 * Immutable, worker-safe representation of block state plus optional serialized block-entity data.
 * Constructor collections are defensively copied so callers cannot mutate queued audit state.
 */
class BlockStateSnapshot(
    val blockId: ResourceId,
    properties: Map<String, String> = emptyMap(),
    val blockEntityData: BinaryPayload? = null
) {
    val properties: Map<String, String> = properties.toMap()

    init {
        require(this.properties.keys.none(String::isBlank)) { "Block-state property names cannot be blank" }
    }

    override fun equals(other: Any?): Boolean =
        other is BlockStateSnapshot &&
            blockId == other.blockId &&
            properties == other.properties &&
            blockEntityData == other.blockEntityData

    override fun hashCode(): Int {
        var result = blockId.hashCode()
        result = 31 * result + properties.hashCode()
        result = 31 * result + (blockEntityData?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "BlockStateSnapshot(blockId=$blockId, properties=$properties, blockEntityData=$blockEntityData)"
}
