package com.bareminimumstudios.guardian.storage.jdbc

import java.nio.file.Path
import java.sql.Connection

class SqliteStorageBackend(path: Path) : JdbcStorageBackend(
    id = "sqlite",
    path = path,
    jdbcUrl = { "jdbc:sqlite:${it.toAbsolutePath()}" },
    driverClassName = "org.sqlite.JDBC"
) {
    override fun configureConnection(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA journal_mode = WAL")
            statement.execute("PRAGMA synchronous = FULL")
            statement.execute("PRAGMA busy_timeout = 5000")
        }
    }

    override fun checkpoint(connection: Connection) {
        connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(PASSIVE)") }
    }

    override fun runIntegrityCheck(connection: Connection): IntegrityResult {
        val passed = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA quick_check").use { result ->
                result.next() && result.getString(1).equals("ok", ignoreCase = true)
            }
        }
        return IntegrityResult(performed = true, passed = passed)
    }
}
