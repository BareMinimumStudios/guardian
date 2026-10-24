package com.bareminimumstudios.guardian.domain

import java.util.UUID

enum class FluidAuditKind { PLACE, REMOVE, FLOW, FORM }

data class FluidSnapshot @JvmOverloads constructor(val fluidId: ResourceId, val source: Boolean, val blockId: ResourceId, val amount: Int? = null) {
    val empty: Boolean get() = fluidId == EMPTY
    init { require(fluidId != EMPTY || !source); require(amount == null || amount in 0..8); require(amount == null || if(empty) amount == 0 else amount in 1..8) }
    companion object { val EMPTY = ResourceId.parse("minecraft:empty") }
}

/** Read-only fluid history; no live state, block-entity payload or rollback journal. */
data class FluidAuditEvent(
    val eventId: UUID = UUID.randomUUID(),
    override val timestampEpochMillis: Long,
    override val actor: ActorIdentity,
    val dimension: ResourceId,
    val position: BlockPosition,
    val kind: FluidAuditKind,
    val before: FluidSnapshot,
    val after: FluidSnapshot
) : LogEntry {
    init {
        require(timestampEpochMillis >= 0)
        require(actor !is ActorIdentity.Player || actor.lastKnownName?.length in 1..64)
        require(before != after)
        when(kind) {
            FluidAuditKind.PLACE -> require(actor is ActorIdentity.Player && !after.empty)
            FluidAuditKind.REMOVE -> require(actor is ActorIdentity.Player && !before.empty && after.empty)
            FluidAuditKind.FLOW -> require(!before.empty || !after.empty)
            FluidAuditKind.FORM -> require(!before.empty && after.empty && before.blockId != after.blockId)
        }
    }
    override val action: ActionType get() = when(kind) { FluidAuditKind.PLACE -> ActionType.FLUID_PLACE; FluidAuditKind.REMOVE -> ActionType.FLUID_REMOVE; FluidAuditKind.FLOW -> ActionType.FLUID_FLOW; FluidAuditKind.FORM -> ActionType.FLUID_FORM }
}
