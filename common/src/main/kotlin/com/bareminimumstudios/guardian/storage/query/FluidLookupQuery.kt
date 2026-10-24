package com.bareminimumstudios.guardian.storage.query

import com.bareminimumstudios.guardian.domain.*
import kotlin.math.abs

data class FluidLookupQuery(
    val kinds: Set<FluidAuditKind> = FluidAuditKind.entries.toSet(),
    val dimension: ResourceId? = null,
    val position: BlockPosition? = null,
    val radius: Int? = null,
    val bounds: BlockBounds? = null,
    val afterEpochMillis: Long? = null,
    val search: HistorySearchFilter = HistorySearchFilter(),
    val limit: Int = 20,
    val offset: Int = 0,
    val oldestFirst: Boolean = false
) {
    init {
        require(kinds.isNotEmpty() && limit in 1..500 && offset in 0..5_000_000)
        require(afterEpochMillis == null || afterEpochMillis >= 0)
        require(bounds == null || position == null && radius == null)
        require(radius == null || position != null && radius in 0..10_000)
        require(dimension != null || position == null && bounds == null)
    }
    fun matches(row: FluidAuditEvent): Boolean = row.kind in kinds &&
        (dimension == null || row.dimension == dimension) &&
        (afterEpochMillis == null || row.timestampEpochMillis >= afterEpochMillis) &&
        search.matchesActor(row.actor) && search.matchesResources(setOf(row.before.fluidId,row.after.fluidId,row.before.blockId,row.after.blockId)) &&
        (bounds == null || bounds.contains(row.position)) &&
        (position == null || abs(row.position.x.toLong()-position.x) <= (radius ?: 0) &&
            abs(row.position.y.toLong()-position.y) <= (radius ?: 0) && abs(row.position.z.toLong()-position.z) <= (radius ?: 0))
}
