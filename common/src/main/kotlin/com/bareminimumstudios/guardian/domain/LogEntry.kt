package com.bareminimumstudios.guardian.domain

sealed interface LogEntry {
    val timestampEpochMillis: Long
    val actor: ActorIdentity
    val action: ActionType
}
