package com.bareminimumstudios.guardian.domain

import java.util.UUID

enum class PlayerActivityKind { CHAT, LOGIN, LOGOUT }

/** Plain immutable text only: no IP, signed-chat material, components or commands. */
data class PlayerActivity(
    val eventId: UUID = UUID.randomUUID(),
    override val timestampEpochMillis: Long,
    override val actor: ActorIdentity.Player,
    val kind: PlayerActivityKind,
    val text: String? = null
) : LogEntry {
    init {
        require(timestampEpochMillis >= 0)
        require(actor.lastKnownName != null && actor.lastKnownName.length in 1..64)
        require(if (kind == PlayerActivityKind.CHAT) text != null && text.length in 1..4096 else text == null)
    }
    override val action: ActionType get() = if (kind == PlayerActivityKind.CHAT) ActionType.CHAT else ActionType.PLAYER_SESSION
}
