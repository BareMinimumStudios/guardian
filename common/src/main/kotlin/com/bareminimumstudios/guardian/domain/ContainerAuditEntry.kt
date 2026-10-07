package com.bareminimumstudios.guardian.domain

data class ContainerAuditEntry(val transaction: ContainerTransactionSnapshot) : LogEntry {
    override val timestampEpochMillis get() = transaction.timestampEpochMillis
    override val actor get() = transaction.actor
    override val action = if (transaction.containers.isEmpty()) ActionType.ITEM_CHANGE else ActionType.CONTAINER_CHANGE
}
