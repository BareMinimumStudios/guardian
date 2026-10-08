package com.bareminimumstudios.guardian.storage

data class StorageHealth(
    val backendId: String,
    val schemaVersion: Int,
    val uncleanShutdownDetected: Boolean = false,
    val integrityCheckPerformed: Boolean = false,
    val integrityCheckPassed: Boolean = true,
    val unfinishedItemRollbacks: Long = 0
)
