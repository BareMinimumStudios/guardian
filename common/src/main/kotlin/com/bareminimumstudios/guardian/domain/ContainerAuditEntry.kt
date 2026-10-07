package com.bareminimumstudios.guardian.domain

data class ContainerAuditEntry(val transaction: ContainerTransactionSnapshot) : LogEntry {
    override val timestampEpochMillis get() = transaction.timestampEpochMillis
    override val actor get() = transaction.actor
    override val action = ActionType.CONTAINER_CHANGE
}
