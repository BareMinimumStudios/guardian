package com.bareminimumstudios.guardian.storage.query

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID

data class ContainerLookupQuery(val dimension: ResourceId? = null, val position: BlockPosition? = null, val actorUuid: UUID? = null, val limit: Int = 10, val actorName: String? = null, val afterEpochMillis: Long? = null, val radius: Int? = null, val bounds: BlockBounds? = null, val offset: Int = 0, val oldestFirst: Boolean = false, val search: HistorySearchFilter = HistorySearchFilter(),val entityUuid:UUID?=null) {
    init { require(offset >= 0); require(limit in 1..500); require(actorName == null || actorName.isNotBlank()); require(position == null || dimension != null); require(bounds == null || (dimension != null && position == null && radius == null)); require(radius == null || (position != null && radius in 0..10000)) }
    fun matches(value: ContainerTransactionSnapshot): Boolean =
        (entityUuid==null || value.changes.any { (it.address.owner as? ItemSlotOwner.EntityContainer)?.entityUuid==entityUuid }) &&
        search.matchesActor(value.actor) && search.matchesResources(value.changes.flatMap { listOfNotNull(it.before.itemId, it.after.itemId) }.toSet()) &&
        (afterEpochMillis == null || value.timestampEpochMillis >= afterEpochMillis) &&
        (actorUuid == null || actorUuid == (value.actor as? ActorIdentity.Player)?.uuid) &&
        (actorName == null || actorName.equals((value.actor as? ActorIdentity.Player)?.lastKnownName, ignoreCase = true)) &&
        (dimension == null || value.containers.any { owner ->
            owner.dimension == dimension && (bounds == null || bounds.contains(owner.position)) && (position == null || if (radius == null) owner.position == position else kotlin.math.abs(owner.position.x.toLong() - position.x) <= radius && kotlin.math.abs(owner.position.y.toLong() - position.y) <= radius && kotlin.math.abs(owner.position.z.toLong() - position.z) <= radius)
        })
}
