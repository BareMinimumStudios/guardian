package com.bareminimumstudios.guardian.storage.jdbc

import java.nio.file.Files
import kotlin.test.Test

class SqliteStorageBackendTest : JdbcBackendContract() {
    override fun backend(path: java.nio.file.Path) = SqliteStorageBackend(path)

    @Test
    fun persistsAndQueriesAcrossRestart() {
        val dir = Files.createTempDirectory("guardian-sqlite-test")
        verifyRoundTrip(dir.resolve("audit.sqlite"))
    }

    @Test
    fun refusesConcurrentOwners() {
        val dir = Files.createTempDirectory("guardian-sqlite-lock")
        verifyExclusiveLock(dir.resolve("audit.sqlite"))
    }
    @Test
    fun queriesInclusiveCuboidBounds() {
        val dir = Files.createTempDirectory("guardian-sqlite-bounds")
        verifyBoundsQuery(dir.resolve("audit.sqlite"))
    }

}
