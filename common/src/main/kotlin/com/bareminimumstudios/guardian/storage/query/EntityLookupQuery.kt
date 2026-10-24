package com.bareminimumstudios.guardian.storage.query

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import kotlin.math.abs

data class EntityLookupQuery(
    val kinds: Set<EntityAuditKind> = EntityAuditKind.entries.toSet(),
    val targetUuid: UUID? = null,
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
    fun matches(row: EntityAuditEvent): Boolean = row.kind in kinds &&
        (targetUuid == null || row.targetUuid == targetUuid) &&
        (dimension == null || row.dimension == dimension) &&
        (afterEpochMillis == null || row.timestampEpochMillis >= afterEpochMillis) &&
        search.matchesActor(row.actor) && search.matchesResources(setOf(row.targetType)) &&
        (bounds == null || bounds.contains(row.position)) &&
        (position == null || abs(row.position.x.toLong()-position.x) <= (radius ?: 0) &&
            abs(row.position.y.toLong()-position.y) <= (radius ?: 0) && abs(row.position.z.toLong()-position.z) <= (radius ?: 0))
}
