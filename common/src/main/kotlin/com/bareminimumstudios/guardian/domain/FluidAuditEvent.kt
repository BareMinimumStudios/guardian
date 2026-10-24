package com.bareminimumstudios.guardian.domain

import java.util.UUID

enum class FluidAuditKind { PLACE, REMOVE }

data class FluidSnapshot(val fluidId: ResourceId, val source: Boolean, val blockId: ResourceId) {
    val empty: Boolean get() = fluidId == EMPTY
    init { require(fluidId != EMPTY || !source) }
    companion object { val EMPTY = ResourceId.parse("minecraft:empty") }
}

/** Read-only bucket history; no live state, block-entity payload or rollback journal. */
data class FluidAuditEvent(
    val eventId: UUID = UUID.randomUUID(),
    override val timestampEpochMillis: Long,
    override val actor: ActorIdentity.Player,
    val dimension: ResourceId,
    val position: BlockPosition,
    val kind: FluidAuditKind,
    val before: FluidSnapshot,
    val after: FluidSnapshot
) : LogEntry {
    init {
        require(timestampEpochMillis >= 0 && actor.lastKnownName?.length in 1..64)
        require(before.fluidId != after.fluidId || before.source != after.source)
        require(if(kind==FluidAuditKind.PLACE) !after.empty else !before.empty && after.empty)
    }
    override val action: ActionType get() = if(kind==FluidAuditKind.PLACE) ActionType.FLUID_PLACE else ActionType.FLUID_REMOVE
}
