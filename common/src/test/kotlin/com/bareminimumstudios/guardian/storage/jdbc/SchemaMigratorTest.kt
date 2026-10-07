package com.bareminimumstudios.guardian.storage.jdbc

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SchemaMigratorTest {
    @Test
    fun createsCurrentSchemaAndIsIdempotent() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            assertEquals(GuardianSchema.CURRENT_VERSION, SchemaMigrator().migrate(connection))
            assertEquals(GuardianSchema.CURRENT_VERSION, SchemaMigrator().migrate(connection))
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM ex_schema_migrations").use { result ->
                    assertTrue(result.next())
                    assertEquals(GuardianSchema.CURRENT_VERSION, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun rejectsMigrationDefinitionGaps() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            val migrator = SchemaMigrator(
                listOf(
                    SchemaMigration(1, "one", listOf("CREATE TABLE one(id INTEGER)")),
                    SchemaMigration(3, "three", listOf("CREATE TABLE three(id INTEGER)"))
                )
            )
            assertFailsWith<IllegalArgumentException> { migrator.migrate(connection) }
        }
    }

    @Test
    fun rejectsDatabaseFromNewerSchema() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            SchemaMigrator().migrate(connection)
            connection.prepareStatement(
                "INSERT INTO ex_schema_migrations(version, applied_at, description) VALUES (999, ?, 'future')"
            ).use { statement ->
                statement.setLong(1, System.currentTimeMillis())
                statement.executeUpdate()
            }
            assertFailsWith<IllegalArgumentException> { SchemaMigrator().migrate(connection) }
        }
    }
}
