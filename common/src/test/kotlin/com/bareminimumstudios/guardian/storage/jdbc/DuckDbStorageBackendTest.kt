package com.bareminimumstudios.guardian.storage.jdbc

import java.nio.file.Files
import kotlin.test.Test

class DuckDbStorageBackendTest : JdbcBackendContract() {
    override fun backend(path: java.nio.file.Path) = DuckDbStorageBackend(path)

    @Test
    fun persistsAndQueriesAcrossRestart() {
        val dir = Files.createTempDirectory("guardian-duckdb-test")
        verifyRoundTrip(dir.resolve("audit.duckdb"))
    }

    @Test
    fun refusesConcurrentOwners() {
        val dir = Files.createTempDirectory("guardian-duckdb-lock")
        verifyExclusiveLock(dir.resolve("audit.duckdb"))
    }
    @Test
    fun queriesInclusiveCuboidBounds() {
        val dir = Files.createTempDirectory("guardian-duckdb-bounds")
        verifyBoundsQuery(dir.resolve("audit.duckdb"))
    }

}
