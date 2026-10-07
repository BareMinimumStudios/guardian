package com.bareminimumstudios.guardian.storage.query

import com.bareminimumstudios.guardian.domain.ActionType
import com.bareminimumstudios.guardian.domain.BlockPosition
import com.bareminimumstudios.guardian.domain.BlockBounds
import com.bareminimumstudios.guardian.domain.ResourceId
import java.util.UUID

data class BlockLookupQuery(
    val dimension: ResourceId? = null,
    val position: BlockPosition? = null,
    /** Optional inclusive cuboid. Mutually exclusive with [position]/[radius]. */
    val bounds: BlockBounds? = null,
    /** Optional cuboid radius around [position]. Null means exact position when position is set. */
    val radius: Int? = null,
    val actorUuid: UUID? = null,
    val actorName: String? = null,
    val actions: Set<ActionType> = emptySet(),
    val afterEpochMillis: Long? = null,
    val beforeEpochMillis: Long? = null,
    val includeRolledBack: Boolean = true,
    val limit: Int = 100
) {
    init {
        require(limit in 1..10_000) { "Lookup limit must be between 1 and 10,000" }
        if (afterEpochMillis != null && beforeEpochMillis != null) {
            require(afterEpochMillis <= beforeEpochMillis) { "afterEpochMillis must not exceed beforeEpochMillis" }
        }
        require(actions.all { it == ActionType.BLOCK_PLACE || it == ActionType.BLOCK_BREAK || it == ActionType.BLOCK_CHANGE }) {
            "BlockLookupQuery only accepts block actions"
        }
        require(bounds == null || position == null) { "A lookup cannot combine bounds with a point/radius" }
        require(bounds == null || radius == null) { "A lookup cannot combine bounds with a radius" }
        radius?.let {
            require(position != null) { "A lookup radius requires a center position" }
            require(it in 0..10_000) { "Lookup radius must be between 0 and 10,000 blocks" }
        }
    }
}
