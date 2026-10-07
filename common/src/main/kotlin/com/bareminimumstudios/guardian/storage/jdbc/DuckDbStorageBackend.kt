package com.bareminimumstudios.guardian.storage.jdbc

import java.nio.file.Path
import java.sql.Connection

class DuckDbStorageBackend(path: Path) : JdbcStorageBackend(
    id = "duckdb",
    path = path,
    jdbcUrl = { "jdbc:duckdb:${it.toAbsolutePath()}" },
    driverClassName = "org.duckdb.DuckDBDriver"
) {
    override fun checkpoint(connection: Connection) {
        connection.createStatement().use { it.execute("CHECKPOINT") }
    }
}
