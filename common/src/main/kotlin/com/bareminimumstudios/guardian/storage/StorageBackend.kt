package com.bareminimumstudios.guardian.storage

import com.bareminimumstudios.guardian.domain.LogEntry

/**
 * Storage boundary for background audit writes.
 * Implementations must be thread-safe for one writer thread and must either persist an entire batch
 * or throw before reporting success.
 */
interface StorageBackend : AutoCloseable {
    val id: String

    fun open()
    fun append(entries: List<LogEntry>)
    fun flush()
    override fun close()
}
