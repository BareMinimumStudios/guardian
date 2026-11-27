package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.ActorIdentity
import com.bareminimumstudios.guardian.domain.BlockPosition
import com.bareminimumstudios.guardian.domain.EntityAuditEvent
import com.bareminimumstudios.guardian.domain.EntityAuditKind
import com.bareminimumstudios.guardian.domain.FluidAuditEvent
import com.bareminimumstudios.guardian.domain.FluidAuditKind
import com.bareminimumstudios.guardian.domain.FluidSnapshot
import com.bareminimumstudios.guardian.domain.PlayerActivity
import com.bareminimumstudios.guardian.domain.PlayerActivityKind
import com.bareminimumstudios.guardian.domain.ResourceId
import java.nio.file.Path
import java.util.UUID
import kotlin.system.measureNanoTime
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class AuditAppendBenchmark {
    @TempDir
    lateinit var directory: Path

    @Test
    fun sqlite() {
        assumeTrue(System.getenv("GUARDIAN_AUDIT_BENCHMARK") == "sqlite")
        repeat(7) { run -> measure("sqlite", run, 72, SqliteStorageBackend(directory.resolve("sqlite-$run.db"))) }
    }

    @Test
    fun duckdb() {
        assumeTrue(System.getenv("GUARDIAN_AUDIT_BENCHMARK") == "duckdb")
        repeat(3) { run -> measure("duckdb", run, 4, DuckDbStorageBackend(directory.resolve("duck-$run.db"))) }
    }

    private fun measure(label: String, run: Int, batchCount: Int, backend: JdbcStorageBackend) {
        val actor = ActorIdentity.Player(UUID(0, 1), "Benchmark")
        val world = ResourceId.parse("minecraft:overworld")
        val pig = ResourceId.parse("minecraft:pig")
        val position = BlockPosition(0, 64, 0)
        val air = FluidSnapshot(FluidSnapshot.EMPTY, false, ResourceId.parse("minecraft:air"))
        val waterId = ResourceId.parse("minecraft:water")
        val water = FluidSnapshot(waterId, true, waterId)
        val batches = List(batchCount) { batch ->
            List(32) { index ->
                val time = (batch * 32 + index).toLong()
                listOf(
                    EntityAuditEvent(timestampEpochMillis = time, actor = actor, dimension = world,
                        position = position, targetUuid = UUID(0, time), targetType = pig, kind = EntityAuditKind.DEATH),
                    FluidAuditEvent(timestampEpochMillis = time, actor = actor, dimension = world,
                        position = position, kind = FluidAuditKind.PLACE, before = air, after = water),
                    PlayerActivity(timestampEpochMillis = time, actor = actor, kind = PlayerActivityKind.CHAT, text = "benchmark"),
                )
            }.flatten()
        }
        backend.use {
            val open = measureNanoTime { it.open() }
            val write = measureNanoTime { batches.forEach(it::append) }
            println("GUARDIAN_BENCH $label run=$run rows=${batchCount * 96} openNs=$open writeNs=$write")
        }
    }
}
