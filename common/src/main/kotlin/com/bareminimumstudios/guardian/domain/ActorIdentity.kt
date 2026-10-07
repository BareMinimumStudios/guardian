package com.bareminimumstudios.guardian.domain

import java.util.UUID

sealed interface ActorIdentity {
    data class Player(val uuid: UUID, val lastKnownName: String?) : ActorIdentity
    data class Entity(val uuid: UUID?, val entityType: ResourceId) : ActorIdentity
    data class System(val source: String) : ActorIdentity
    data object Unknown : ActorIdentity
}
