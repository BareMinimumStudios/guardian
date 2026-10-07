package com.bareminimumstudios.guardian.storage.query

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID

data class ContainerLookupQuery(val dimension: ResourceId? = null, val position: BlockPosition? = null, val actorUuid: UUID? = null, val limit: Int = 10) {
    init { require(limit in 1..500); require(position == null || dimension != null) }
    fun matches(value: ContainerTransactionSnapshot): Boolean =
        (actorUuid == null || actorUuid == value.actor.uuid) &&
        (dimension == null || value.containers.any { owner ->
            owner.dimension == dimension && (position == null || owner.position == position)
        })
}
