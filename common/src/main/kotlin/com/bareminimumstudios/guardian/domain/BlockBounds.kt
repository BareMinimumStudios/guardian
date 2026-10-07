package com.bareminimumstudios.guardian.domain

/** Inclusive cuboid bounds used by region-backed lookups such as WorldEdit selections. */
data class BlockBounds(
    val min: BlockPosition,
    val max: BlockPosition
) {
    init {
        require(min.x <= max.x && min.y <= max.y && min.z <= max.z) {
            "BlockBounds minimum must not exceed maximum"
        }
    }

    fun contains(position: BlockPosition): Boolean =
        position.x in min.x..max.x &&
            position.y in min.y..max.y &&
            position.z in min.z..max.z

    val volume: Long
        get() = (max.x.toLong() - min.x + 1L) *
            (max.y.toLong() - min.y + 1L) *
            (max.z.toLong() - min.z + 1L)
}
