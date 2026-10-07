package com.bareminimumstudios.guardian.logging.bulk

data class BulkAuditMetrics(
    val reservedOperations: Int,
    val queuedOperations: Int,
    val queuedEntries: Long,
    val streamedChunks: Long,
    val submittedEntries: Long,
    val backpressureRetries: Long,
    val rejectedReservations: Long,
    val failed: Boolean
)
