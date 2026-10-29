package com.bareminimumstudios.guardian.domain

import java.util.UUID

enum class PlayerActivityKind { CHAT, LOGIN, LOGOUT, COMMAND, WHISPER }

/** Plain immutable text only: no signed-chat material or components. Commands pass a separate redaction policy. */
data class PlayerActivity @JvmOverloads constructor(
    val eventId: UUID = UUID.randomUUID(),
    override val timestampEpochMillis: Long,
    override val actor: ActorIdentity.Player,
    val kind: PlayerActivityKind,
    val text: String? = null,
    val recipientUuid: UUID? = null,
    val recipientName: String? = null
) : LogEntry {
    init {
        require(timestampEpochMillis >= 0)
        require(if(kind==PlayerActivityKind.WHISPER)recipientUuid!=null && recipientName!=null && recipientName.length in 1..64 else recipientUuid==null && recipientName==null)
        require(actor.lastKnownName != null && actor.lastKnownName.length in 1..64)
        require(if (kind == PlayerActivityKind.CHAT || kind == PlayerActivityKind.COMMAND || kind == PlayerActivityKind.WHISPER) text != null && text.length in 1..4096 else text == null)
    }
    override val action: ActionType get() = when(kind) { PlayerActivityKind.CHAT -> ActionType.CHAT; PlayerActivityKind.WHISPER -> ActionType.WHISPER; PlayerActivityKind.COMMAND -> ActionType.COMMAND; else -> ActionType.PLAYER_SESSION }
}
