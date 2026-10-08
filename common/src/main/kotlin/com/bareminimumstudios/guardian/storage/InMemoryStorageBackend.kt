package com.bareminimumstudios.guardian.storage

import com.bareminimumstudios.guardian.domain.ActorIdentity
import com.bareminimumstudios.guardian.domain.BlockChangeSnapshot
import com.bareminimumstudios.guardian.domain.LogEntry
import com.bareminimumstudios.guardian.storage.query.BlockLookupQuery
import com.bareminimumstudios.guardian.storage.query.BlockRollbackState
import com.bareminimumstudios.guardian.storage.query.StoredBlockChange
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.abs

/** Development/test backend. Deliberately non-persistent. */
class InMemoryStorageBackend : QueryableStorageBackend {
    private val lock = ReentrantReadWriteLock()
    private val entries = mutableListOf<MemoryBlockRow>()
    private val containers = mutableListOf<com.bareminimumstudios.guardian.domain.ContainerTransactionSnapshot>()
    private var nextRowId = 1L
    @Volatile private var open = false

    override val id: String = "memory"

    override fun open() {
        open = true
    }

    override fun append(entries: List<LogEntry>) {
        check(open) { "Storage backend is not open" }
        lock.write {
            entries.filterIsInstance<com.bareminimumstudios.guardian.domain.ContainerAuditEntry>().forEach { entry ->
                if (containers.none { it.transactionId == entry.transaction.transactionId }) containers += entry.transaction
            }
            entries.filterIsInstance<BlockChangeSnapshot>().forEach { snapshot ->
                if (this.entries.none { it.snapshot.eventId == snapshot.eventId }) {
                    this.entries += MemoryBlockRow(nextRowId++, snapshot, BlockRollbackState.ACTIVE)
                }
            }
        }
    }

    override fun lookupBlocks(query: BlockLookupQuery): List<StoredBlockChange> = lock.read {
        entries.asSequence()
            .filter { query.dimension == null || it.snapshot.dimension == query.dimension }
            .filter { row ->
                val bounds = query.bounds
                if (bounds != null) return@filter bounds.contains(row.snapshot.position)
                val center = query.position ?: return@filter true
                val radius = query.radius
                if (radius == null || radius == 0) row.snapshot.position == center
                else abs(row.snapshot.position.x - center.x) <= radius &&
                    abs(row.snapshot.position.y - center.y) <= radius &&
                    abs(row.snapshot.position.z - center.z) <= radius
            }
            .filter { query.includeRolledBack || it.rollbackState != BlockRollbackState.ROLLED_BACK }
            .filter { query.actions.isEmpty() || it.snapshot.action in query.actions }
            .filter { query.afterEpochMillis == null || it.snapshot.timestampEpochMillis >= query.afterEpochMillis }
            .filter { query.beforeEpochMillis == null || it.snapshot.timestampEpochMillis <= query.beforeEpochMillis }
            .filter { row ->
                when {
                    query.actorUuid == null -> true
                    row.snapshot.actor is ActorIdentity.Player -> row.snapshot.actor.uuid == query.actorUuid
                    row.snapshot.actor is ActorIdentity.Entity -> row.snapshot.actor.uuid == query.actorUuid
                    else -> false
                }
            }
            .filter { row ->
                query.actorName == null ||
                    (row.snapshot.actor is ActorIdentity.Player && row.snapshot.actor.lastKnownName.equals(query.actorName, ignoreCase = true))
            }
            .sortedWith(compareByDescending<MemoryBlockRow> { it.snapshot.timestampEpochMillis }.thenByDescending { it.rowId })
            .drop(query.offset).take(query.limit)
            .map { StoredBlockChange(it.rowId, it.snapshot, it.rollbackState) }
            .toList()
    }

    override fun setBlockRollbackState(rowIds: Collection<Long>, state: BlockRollbackState): Int = lock.write {
        if (rowIds.isEmpty()) return@write 0
        val wanted = rowIds.toHashSet()
        var changed = 0
        entries.forEachIndexed { index, row ->
            if (row.rowId in wanted && row.rollbackState != state) {
                entries[index] = row.copy(rollbackState = state)
                changed++
            }
        }
        changed
    }

    override fun lookupContainers(query: com.bareminimumstudios.guardian.storage.query.ContainerLookupQuery) = lock.read {
        containers.filter(query::matches).sortedWith(compareByDescending<com.bareminimumstudios.guardian.domain.ContainerTransactionSnapshot> { it.timestampEpochMillis }.thenByDescending { it.transactionId.toString() }).drop(query.offset).take(query.limit)
    }

    override fun flush() = Unit

    fun snapshot(): List<LogEntry> = lock.read { entries.map { it.snapshot } + containers.map { com.bareminimumstudios.guardian.domain.ContainerAuditEntry(it) } }

    override fun health(): StorageHealth = StorageHealth(id, schemaVersion = 0)

    override fun close() {
        open = false
    }

    private data class MemoryBlockRow(
        val rowId: Long,
        val snapshot: BlockChangeSnapshot,
        val rollbackState: BlockRollbackState
    )
}
