package com.bareminimumstudios.guardian.storage.jdbc

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MissingJdbcDriverTest {
    private fun missing(path: Path) = object : JdbcStorageBackend(
        "duckdb", path, { "jdbc:duckdb:$it" }, "guardian.test.UnavailableJdbcDriver"
    ) {}

    @Test fun unavailableDriverFailsBeforeCreatingStorageFiles() {
        val root = Files.createTempDirectory("guardian-missing-driver")
        val directory = root.resolve("not-created")
        val error = assertFailsWith<IllegalStateException> { missing(directory.resolve("history.duckdb")).open() }
        assertTrue(error.message!!.contains("Standard builds bundle SQLite only"))
        assertTrue(error.message!!.contains("No database fallback"))
        assertIs<ClassNotFoundException>(error.cause)
        assertFalse(Files.exists(directory))
    }

    @Test fun unavailableDriverPreservesExistingDatabaseWithoutCreatingFallbackOrLock() {
        val directory = Files.createTempDirectory("guardian-existing-driver")
        val path = directory.resolve("history.duckdb")
        val original = byteArrayOf(1, 7, 9, 23)
        Files.write(path, original)
        assertFailsWith<IllegalStateException> { missing(path).open() }
        assertContentEquals(original, Files.readAllBytes(path))
        Files.list(directory).use { assertEquals(listOf(path), it.toList()) }
    }
}
