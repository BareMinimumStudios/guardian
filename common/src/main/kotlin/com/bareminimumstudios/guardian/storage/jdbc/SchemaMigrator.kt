package com.bareminimumstudios.guardian.storage.jdbc

import java.sql.Connection

internal class SchemaMigrator(
    private val migrations: List<SchemaMigration> = GuardianSchema.migrations
) {
    fun migrate(connection: Connection): Int {
        val orderedMigrations = migrations.sortedBy { it.version }
        val versions = orderedMigrations.map { it.version }
        require(versions == (1..versions.size).toList()) {
            "Schema migrations must be contiguous starting at version 1; found $versions"
        }

        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS ex_schema_migrations (
                    version INTEGER PRIMARY KEY,
                    applied_at BIGINT NOT NULL,
                    description VARCHAR NOT NULL
                )
                """.trimIndent()
            )
        }

        val applied = mutableSetOf<Int>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT version FROM ex_schema_migrations ORDER BY version").use { result ->
                while (result.next()) applied += result.getInt(1)
            }
        }

        val newestApplied = applied.maxOrNull() ?: 0
        val newestKnown = orderedMigrations.maxOfOrNull { it.version } ?: 0
        require(newestApplied <= newestKnown) {
            "Database schema $newestApplied is newer than this Guardian build supports ($newestKnown)"
        }
        require(applied == (1..newestApplied).toSet()) {
            "Database migration history has gaps: ${applied.sorted()}"
        }

        for (migration in orderedMigrations) {
            if (migration.version in applied) continue
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    migration.statements.forEach(statement::executeUpdate)
                }
                connection.prepareStatement(
                    "INSERT INTO ex_schema_migrations(version, applied_at, description) VALUES (?, ?, ?)"
                ).use { statement ->
                    statement.setInt(1, migration.version)
                    statement.setLong(2, System.currentTimeMillis())
                    statement.setString(3, migration.description)
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (t: Throwable) {
                connection.rollback()
                throw t
            } finally {
                connection.autoCommit = true
            }
        }

        return newestKnown
    }
}
