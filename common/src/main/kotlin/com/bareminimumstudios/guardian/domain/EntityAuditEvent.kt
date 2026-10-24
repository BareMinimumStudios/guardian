package com.bareminimumstudios.guardian.domain

import java.util.UUID

enum class EntityAuditKind { DEATH, NAME_CHANGE }

/** Only immutable identity, location and bounded plain names; no live entity or NBT payload. */
data class EntityAuditEvent(
    val eventId: UUID = UUID.randomUUID(),
    override val timestampEpochMillis: Long,
    override val actor: ActorIdentity,
    val dimension: ResourceId,
    val position: BlockPosition,
    val targetUuid: UUID,
    val targetType: ResourceId,
    val kind: EntityAuditKind,
    val beforeName: String? = null,
    val afterName: String? = null,
    val damageType: ResourceId? = null
) : LogEntry {
    init {
        require(timestampEpochMillis >= 0)
        require(listOfNotNull(beforeName, afterName).all { it.length <= 256 && it.none(Char::isISOControl) })
        require(if (kind == EntityAuditKind.NAME_CHANGE) beforeName != afterName && damageType == null else afterName == null)
    }
    override val action: ActionType get() = if (kind == EntityAuditKind.DEATH) ActionType.ENTITY_DEATH else ActionType.ENTITY_NAME_CHANGE
}
