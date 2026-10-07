package com.bareminimumstudios.guardian.storage.query

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID

data class ContainerLookupQuery(val dimension: ResourceId? = null, val position: BlockPosition? = null, val actorUuid: UUID? = null, val limit: Int = 10, val actorName: String? = null) {
    init { require(limit in 1..500); require(actorName == null || actorName.isNotBlank()); require(position == null || dimension != null) }
    fun matches(value: ContainerTransactionSnapshot): Boolean =
        (actorUuid == null || actorUuid == value.actor.uuid) &&
        (actorName == null || actorName.equals(value.actor.lastKnownName, ignoreCase = true)) &&
        (dimension == null || value.containers.any { owner ->
            owner.dimension == dimension && (position == null || owner.position == position)
        })
}
