package com.bareminimumstudios.guardian.domain

import java.util.UUID

enum class EntityAuditKind { DEATH, NAME_CHANGE, TAME, BREED }

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
    val damageType: ResourceId? = null,
    val parentUuid: UUID? = null,
    val partnerUuid: UUID? = null
) : LogEntry {
    init {
        require(timestampEpochMillis >= 0)
        require(listOfNotNull(beforeName, afterName).all { it.length <= 256 && it.none(Char::isISOControl) })
        require(if (kind == EntityAuditKind.NAME_CHANGE) beforeName != afterName && damageType == null else afterName == null)
        require(kind == EntityAuditKind.DEATH || damageType == null)
        require(kind != EntityAuditKind.TAME || actor is ActorIdentity.Player)
        require(if (kind == EntityAuditKind.BREED) parentUuid != null && partnerUuid != null && parentUuid != partnerUuid && targetUuid != parentUuid && targetUuid != partnerUuid else parentUuid == null && partnerUuid == null)
    }
    override val action: ActionType get() = when(kind) {
        EntityAuditKind.DEATH -> ActionType.ENTITY_DEATH
        EntityAuditKind.NAME_CHANGE -> ActionType.ENTITY_NAME_CHANGE
        EntityAuditKind.TAME -> ActionType.ENTITY_TAME
        EntityAuditKind.BREED -> ActionType.ENTITY_BREED
    }
}
