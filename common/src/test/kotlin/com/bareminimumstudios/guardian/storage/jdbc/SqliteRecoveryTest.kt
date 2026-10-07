package com.bareminimumstudios.guardian.storage.jdbc

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertTrue

class SqliteRecoveryTest {
    @Test
    fun runsQuickCheckAfterUncleanShutdownMarker() {
        Class.forName("org.sqlite.JDBC")
        val path = Files.createTempDirectory("guardian-recovery").resolve("audit.sqlite")
        SqliteStorageBackend(path).use { backend ->
            backend.open()
        }

        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
            connection.createStatement().use {
                it.executeUpdate("UPDATE ex_meta SET meta_value = 'false' WHERE meta_key = 'clean_shutdown'")
            }
        }

        SqliteStorageBackend(path).use { backend ->
            backend.open()
            val health = backend.health()
            assertTrue(health.uncleanShutdownDetected)
            assertTrue(health.integrityCheckPerformed)
            assertTrue(health.integrityCheckPassed)
        }
    }
}
