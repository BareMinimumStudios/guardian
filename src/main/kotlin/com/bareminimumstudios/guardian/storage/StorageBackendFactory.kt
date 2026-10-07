package com.bareminimumstudios.guardian.storage

import com.bareminimumstudios.guardian.config.GuardianConfig
import com.bareminimumstudios.guardian.storage.jdbc.DuckDbStorageBackend
import com.bareminimumstudios.guardian.storage.jdbc.SqliteStorageBackend
import java.nio.file.Path

object StorageBackendFactory {
    fun create(config: GuardianConfig.Storage, root: Path): QueryableStorageBackend = when (config.backend.get()) {
        StorageBackendType.SQLITE -> SqliteStorageBackend(root.resolve(config.sqliteFile.get()))
        StorageBackendType.DUCKDB -> DuckDbStorageBackend(root.resolve(config.duckDbFile.get()))
        StorageBackendType.MEMORY -> InMemoryStorageBackend()
    }
}
