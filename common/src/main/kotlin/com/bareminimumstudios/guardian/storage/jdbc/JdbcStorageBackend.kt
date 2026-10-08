package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.ActionType
import com.bareminimumstudios.guardian.domain.BinaryPayload
import com.bareminimumstudios.guardian.domain.BlockChangeSnapshot
import com.bareminimumstudios.guardian.domain.BlockPosition
import com.bareminimumstudios.guardian.domain.BlockStateSnapshot
import com.bareminimumstudios.guardian.domain.ChangeCause
import com.bareminimumstudios.guardian.domain.LogEntry
import com.bareminimumstudios.guardian.domain.ResourceId
import com.bareminimumstudios.guardian.storage.QueryableStorageBackend
import com.bareminimumstudios.guardian.storage.StorageHealth
import com.bareminimumstudios.guardian.storage.codec.BlockPropertiesCodec
import com.bareminimumstudios.guardian.storage.query.BlockLookupQuery
import com.bareminimumstudios.guardian.storage.query.BlockRollbackState
import com.bareminimumstudios.guardian.storage.query.StoredBlockChange
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.codec.ContainerChangesCodec
import com.bareminimumstudios.guardian.storage.query.ContainerLookupQuery
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

abstract class JdbcStorageBackend(
    final override val id: String,
    private val path: Path,
    private val jdbcUrl: (Path) -> String,
    private val driverClassName: String
) : QueryableStorageBackend {
    private val lock = ReentrantLock()
    private var connection: Connection? = null
    private var fileLockChannel: FileChannel? = null
    private var databaseFileLock: FileLock? = null
    private var storageHealth = StorageHealth(id, 0)

    private val allocator = SequenceAllocator()
    private val worldMappings = MappingRegistry("ex_world_map", "world", "world", allocator)
    private val resourceMappings = MappingRegistry("ex_resource_map", "resource", "resource", allocator)
    private val blockDataMappings = MappingRegistry("ex_blockdata_map", "data", "blockdata", allocator)
    private val actorCodec = ActorDatabaseCodec(allocator)

    override fun open() = lock.withLock {
        check(connection == null) { "Storage backend $id is already open" }
        path.parent?.let(Files::createDirectories)
        acquireDatabaseFileLock()
        val opened = try {
            Class.forName(driverClassName)
            DriverManager.getConnection(jdbcUrl(path))
        } catch (t: Throwable) {
            releaseDatabaseFileLock()
            throw t
        }
        try {
            configureConnection(opened)
            val schemaVersion = SchemaMigrator().migrate(opened)
            val existingFormat = readMeta(opened, META_STORAGE_FORMAT)
            require(existingFormat == null || existingFormat == STORAGE_FORMAT) {
                "Database uses unsupported storage format '$existingFormat'"
            }
            val previousClean = readMeta(opened, META_CLEAN_SHUTDOWN)?.toBooleanStrictOrNull() ?: true
            val persistedCodec = readMeta(opened, META_BLOCK_PROPERTIES_CODEC)?.toIntOrNull()
            require(persistedCodec == null || persistedCodec == BlockPropertiesCodec.VERSION) {
                "Unsupported block-properties codec version $persistedCodec; this build supports ${BlockPropertiesCodec.VERSION}"
            }
            val integrity = if (!previousClean) runIntegrityCheck(opened) else IntegrityResult(false, true)
            check(integrity.passed) { "Storage integrity check failed for $path" }
            writeMeta(opened, META_CLEAN_SHUTDOWN, "false")
            writeMeta(opened, META_SCHEMA_VERSION, schemaVersion.toString())
            writeMeta(opened, META_BLOCK_PROPERTIES_CODEC, BlockPropertiesCodec.VERSION.toString())
            writeMeta(opened, META_STORAGE_FORMAT, STORAGE_FORMAT)
            storageHealth = StorageHealth(
                backendId = id,
                schemaVersion = schemaVersion,
                uncleanShutdownDetected = !previousClean,
                integrityCheckPerformed = integrity.performed,
                integrityCheckPassed = integrity.passed
            )
            connection = opened
        } catch (t: Throwable) {
            runCatching { opened.close() }
            releaseDatabaseFileLock()
            throw t
        }
    }

    override fun append(entries: List<LogEntry>): Unit = lock.withLock {
        if (entries.isEmpty()) return@withLock
        val conn = requireConnection()
        require(entries.all { it is BlockChangeSnapshot || it is ContainerAuditEntry }) {
            "Unsupported audit entry"
        }

        val reservedRowIds = entries.filterIsInstance<BlockChangeSnapshot>().let { blocks -> if (blocks.isEmpty()) null else allocator.reserve(conn, "block", blocks.size).iterator() }
        conn.autoCommit = false
        try {
            conn.prepareStatement(BLOCK_INSERT_SQL).use { insertStatement ->
                entries.forEach {
                    when (it) {
                        is BlockChangeSnapshot -> { bindBlockChange(conn, insertStatement, it, checkNotNull(reservedRowIds).nextLong()); insertStatement.addBatch() }
                        is ContainerAuditEntry -> insertContainer(conn, it.transaction)
                    }
                }
                insertStatement.executeBatch()
            }
            conn.commit()
        } catch (t: Throwable) {
            conn.rollback()
            clearMappingCaches()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    private fun insertContainer(conn: Connection, transaction: ContainerTransactionSnapshot) {
        val bytes = ContainerChangesCodec.encode(transaction.changes)
        val inserted = conn.prepareStatement("INSERT INTO ex_container(transaction_uuid, time, actor, menu_id, interaction, changes) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(transaction_uuid) DO NOTHING").use {
            it.setString(1, transaction.transactionId.toString()); it.setLong(2, transaction.timestampEpochMillis)
            it.setLong(3, checkNotNull(actorCodec.idFor(conn, transaction.actor))); it.setInt(4, transaction.menuId)
            it.setString(5, transaction.action.name); it.setBytes(6, bytes); it.executeUpdate()
        }
        if (inserted == 0) return
        conn.prepareStatement("INSERT INTO ex_container_location(transaction_uuid, wid, x, y, z) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING").use { statement ->
            transaction.containers.forEach { owner ->
                statement.setString(1, transaction.transactionId.toString()); statement.setInt(2, worldMappings.idFor(conn, owner.dimension.toString()))
                statement.setInt(3, owner.position.x); statement.setInt(4, owner.position.y); statement.setInt(5, owner.position.z)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    override fun lookupContainers(query: ContainerLookupQuery): List<ContainerTransactionSnapshot> = lock.withLock {
        val conn = requireConnection()
        val sql = StringBuilder("SELECT c.* FROM ex_container c JOIN ex_actor_map a ON a.id = c.actor WHERE 1=1")
        if (query.actorUuid != null) sql.append(" AND a.uuid = ?")
        if (query.actorName != null) sql.append(" AND LOWER(a.name) = LOWER(?)")
        if (query.dimension != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM ex_container_location l JOIN ex_world_map w ON w.id = l.wid WHERE l.transaction_uuid = c.transaction_uuid AND w.world = ?")
            if (query.position != null || query.bounds != null) sql.append(" AND l.x BETWEEN ? AND ? AND l.y BETWEEN ? AND ? AND l.z BETWEEN ? AND ?")
            sql.append(")")
        }
        if (query.afterEpochMillis != null) sql.append(" AND c.time >= ?")
        sql.append(if (query.oldestFirst) " ORDER BY c.time ASC, c.transaction_uuid ASC LIMIT ? OFFSET ?" else " ORDER BY c.time DESC, c.transaction_uuid DESC LIMIT ? OFFSET ?")
        conn.prepareStatement(sql.toString()).use { statement ->
            var index = 1
            query.actorUuid?.let { statement.setString(index++, it.toString()) }
            query.actorName?.let { statement.setString(index++, it) }
            query.dimension?.let { statement.setString(index++, it.toString()) }
            val center = query.position
            val radius = query.radius ?: 0
            val min = query.bounds?.min ?: center?.let { com.bareminimumstudios.guardian.domain.BlockPosition(it.x - radius, it.y - radius, it.z - radius) }
            val max = query.bounds?.max ?: center?.let { com.bareminimumstudios.guardian.domain.BlockPosition(it.x + radius, it.y + radius, it.z + radius) }
            if (min != null && max != null) { for (coordinate in listOf(min.x, max.x, min.y, max.y, min.z, max.z)) statement.setInt(index++, coordinate) }
            query.afterEpochMillis?.let { statement.setLong(index++, it) }
            statement.setInt(index++, query.limit)
            statement.setInt(index, query.offset)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(ContainerTransactionSnapshot(
                        UUID.fromString(result.getString("transaction_uuid")), result.getLong("time"),
                        actorCodec.actorFor(conn, result.getLong("actor")),
                        result.getInt("menu_id"), ContainerAction.valueOf(result.getString("interaction")),
                        ContainerChangesCodec.decode(result.getBytes(result.findColumn("changes"))),
                        containerLocations(conn, result.getString("transaction_uuid"))
                    ))
                }
            }
        }
    }

    private fun containerLocations(conn: Connection, id: String): List<ItemSlotOwner.BlockContainer> =
        conn.prepareStatement("SELECT wid, x, y, z FROM ex_container_location WHERE transaction_uuid = ? ORDER BY wid, x, y, z").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(ItemSlotOwner.BlockContainer(ResourceId.parse(worldMappings.valueFor(conn, rows.getInt("wid"))), BlockPosition(rows.getInt("x"), rows.getInt("y"), rows.getInt("z"))))
            } }
        }

    override fun lookupBlocks(query: BlockLookupQuery): List<StoredBlockChange> = lock.withLock {
        val conn = requireConnection()
        val sql = StringBuilder(
            """
            SELECT b.rowid, b.event_uuid, b.time, b.actor, b.wid, b.x, b.y, b.z,
                   b.before_type, b.before_data, b.before_meta,
                   b.after_type, b.after_data, b.after_meta,
                   b.cause, b.action, b.rolled_back
            FROM ex_block b
            JOIN ex_world_map w ON w.id = b.wid
            LEFT JOIN ex_actor_map a ON a.id = b.actor
            WHERE 1=1
            """.trimIndent()
        )
        val binders = mutableListOf<(PreparedStatement, Int) -> Unit>()

        query.dimension?.let { dimension ->
            sql.append(" AND w.world = ?")
            binders += { statement, index -> statement.setString(index, dimension.toString()) }
        }
        query.bounds?.let { bounds ->
            sql.append(" AND b.x BETWEEN ? AND ? AND b.y BETWEEN ? AND ? AND b.z BETWEEN ? AND ?")
            binders += { statement, index -> statement.setInt(index, bounds.min.x) }
            binders += { statement, index -> statement.setInt(index, bounds.max.x) }
            binders += { statement, index -> statement.setInt(index, bounds.min.y) }
            binders += { statement, index -> statement.setInt(index, bounds.max.y) }
            binders += { statement, index -> statement.setInt(index, bounds.min.z) }
            binders += { statement, index -> statement.setInt(index, bounds.max.z) }
        }
        query.position?.let { pos ->
            val radius = query.radius
            if (radius == null || radius == 0) {
                sql.append(" AND b.x = ? AND b.y = ? AND b.z = ?")
                binders += { statement, index -> statement.setInt(index, pos.x) }
                binders += { statement, index -> statement.setInt(index, pos.y) }
                binders += { statement, index -> statement.setInt(index, pos.z) }
            } else {
                sql.append(" AND b.x BETWEEN ? AND ? AND b.y BETWEEN ? AND ? AND b.z BETWEEN ? AND ?")
                binders += { statement, index -> statement.setInt(index, pos.x - radius) }
                binders += { statement, index -> statement.setInt(index, pos.x + radius) }
                binders += { statement, index -> statement.setInt(index, pos.y - radius) }
                binders += { statement, index -> statement.setInt(index, pos.y + radius) }
                binders += { statement, index -> statement.setInt(index, pos.z - radius) }
                binders += { statement, index -> statement.setInt(index, pos.z + radius) }
            }
        }
        query.actorUuid?.let { uuid ->
            sql.append(" AND a.uuid = ?")
            binders += { statement, index -> statement.setString(index, uuid.toString()) }
        }
        query.actorName?.let { name ->
            sql.append(" AND LOWER(a.name) = LOWER(?)")
            binders += { statement, index -> statement.setString(index, name) }
        }
        query.afterEpochMillis?.let { after ->
            sql.append(" AND b.time >= ?")
            binders += { statement, index -> statement.setLong(index, after) }
        }
        query.beforeEpochMillis?.let { before ->
            sql.append(" AND b.time <= ?")
            binders += { statement, index -> statement.setLong(index, before) }
        }
        if (!query.includeRolledBack) sql.append(" AND b.rolled_back <> 1")
        if (query.actions.isNotEmpty()) {
            val actions = query.actions.sortedBy { it.storageCode }
            sql.append(" AND b.action IN (")
            sql.append(actions.joinToString(",") { "?" })
            sql.append(')')
            actions.forEach { action ->
                binders += { statement, index -> statement.setInt(index, action.storageCode) }
            }
        }
        sql.append(if (query.oldestFirst) " ORDER BY b.time ASC, b.rowid ASC LIMIT ? OFFSET ?" else " ORDER BY b.time DESC, b.rowid DESC LIMIT ? OFFSET ?")
        binders += { statement, index -> statement.setInt(index, query.limit) }
        binders += { statement, index -> statement.setInt(index, query.offset) }

        conn.prepareStatement(sql.toString()).use { statement ->
            binders.forEachIndexed { index, binder -> binder(statement, index + 1) }
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(readBlockChange(conn, result))
                }
            }
        }
    }


    override fun setBlockRollbackState(rowIds: Collection<Long>, state: BlockRollbackState): Int = lock.withLock {
        if (rowIds.isEmpty()) return 0
        val conn = requireConnection()
        conn.autoCommit = false
        try {
            var changed = 0
            conn.prepareStatement("UPDATE ex_block SET rolled_back = ? WHERE rowid = ? AND rolled_back <> ?").use { statement ->
                rowIds.forEach { rowId ->
                    statement.setInt(1, state.storageCode)
                    statement.setLong(2, rowId)
                    statement.setInt(3, state.storageCode)
                    statement.addBatch()
                }
                changed = statement.executeBatch().sumOf { count ->
                    when {
                        count > 0 -> count
                        count == java.sql.Statement.SUCCESS_NO_INFO -> 1
                        else -> 0
                    }
                }
            }
            conn.commit()
            changed
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    override fun flush() = lock.withLock {
        checkpoint(requireConnection())
    }

    override fun health(): StorageHealth = storageHealth

    override fun close(): Unit = lock.withLock {
        val conn = connection ?: return@withLock
        try {
            checkpoint(conn)
            writeMeta(conn, META_CLEAN_SHUTDOWN, "true")
            checkpoint(conn)
        } finally {
            runCatching { conn.close() }
            connection = null
            clearMappingCaches()
            releaseDatabaseFileLock()
        }
    }

    protected open fun configureConnection(connection: Connection) = Unit
    protected open fun checkpoint(connection: Connection) = Unit
    protected open fun runIntegrityCheck(connection: Connection): IntegrityResult = IntegrityResult(false, true)

    private fun bindBlockChange(
        connection: Connection,
        statement: PreparedStatement,
        snapshot: BlockChangeSnapshot,
        rowId: Long
    ) {
        val actorId = actorCodec.idFor(connection, snapshot.actor)
        val worldId = worldMappings.idFor(connection, snapshot.dimension.toString())
        val beforeType = resourceMappings.idFor(connection, snapshot.before.blockId.toString())
        val beforeData = blockDataMappings.idFor(connection, BlockPropertiesCodec.encode(snapshot.before.properties))
        val afterType = resourceMappings.idFor(connection, snapshot.after.blockId.toString())
        val afterData = blockDataMappings.idFor(connection, BlockPropertiesCodec.encode(snapshot.after.properties))

        statement.clearParameters()
        statement.setLong(1, rowId)
        statement.setString(2, snapshot.eventId.toString())
        statement.setLong(3, snapshot.timestampEpochMillis)
        if (actorId == null) statement.setNull(4, java.sql.Types.BIGINT) else statement.setLong(4, actorId)
        statement.setInt(5, worldId)
        statement.setInt(6, snapshot.position.x)
        statement.setInt(7, snapshot.position.y)
        statement.setInt(8, snapshot.position.z)
        statement.setInt(9, beforeType)
        statement.setInt(10, beforeData)
        setPayload(statement, 11, snapshot.before.blockEntityData)
        statement.setInt(12, afterType)
        statement.setInt(13, afterData)
        setPayload(statement, 14, snapshot.after.blockEntityData)
        statement.setInt(15, snapshot.cause.storageCode)
        statement.setInt(16, snapshot.action.storageCode)
    }

    private fun readBlockChange(connection: Connection, result: ResultSet): StoredBlockChange {
        val before = BlockStateSnapshot(
            blockId = ResourceId.parse(resourceMappings.valueFor(connection, result.getInt("before_type"))),
            properties = BlockPropertiesCodec.decode(blockDataMappings.valueFor(connection, result.getInt("before_data"))),
            blockEntityData = result.getBytes(result.findColumn("before_meta"))?.let(BinaryPayload::of)
        )
        val after = BlockStateSnapshot(
            blockId = ResourceId.parse(resourceMappings.valueFor(connection, result.getInt("after_type"))),
            properties = BlockPropertiesCodec.decode(blockDataMappings.valueFor(connection, result.getInt("after_data"))),
            blockEntityData = result.getBytes(result.findColumn("after_meta"))?.let(BinaryPayload::of)
        )
        val actorColumn = result.getLong("actor")
        val actorId = if (result.wasNull()) null else actorColumn
        val action = ActionType.fromStorageCode(result.getInt("action"))
        val cause = ChangeCause.fromStorageCode(result.getInt("cause"))

        return StoredBlockChange(
            rowId = result.getLong("rowid"),
            snapshot = BlockChangeSnapshot(
                timestampEpochMillis = result.getLong("time"),
                actor = actorCodec.actorFor(connection, actorId),
                dimension = ResourceId.parse(worldMappings.valueFor(connection, result.getInt("wid"))),
                position = BlockPosition(result.getInt("x"), result.getInt("y"), result.getInt("z")),
                before = before,
                after = after,
                cause = cause,
                action = action,
                eventId = UUID.fromString(result.getString("event_uuid"))
            ),
            rollbackState = BlockRollbackState.fromStorageCode(result.getInt("rolled_back"))
        )
    }

    private fun readMeta(connection: Connection, key: String): String? =
        connection.prepareStatement("SELECT meta_value FROM ex_meta WHERE meta_key = ?").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }

    private fun writeMeta(connection: Connection, key: String, value: String) {
        val updated = connection.prepareStatement("UPDATE ex_meta SET meta_value = ? WHERE meta_key = ?").use { statement ->
            statement.setString(1, value)
            statement.setString(2, key)
            statement.executeUpdate()
        }
        if (updated == 0) {
            connection.prepareStatement("INSERT INTO ex_meta(meta_key, meta_value) VALUES (?, ?)").use { statement ->
                statement.setString(1, key)
                statement.setString(2, value)
                statement.executeUpdate()
            }
        }
    }


    private fun acquireDatabaseFileLock() {
        val lockPath = path.resolveSibling(path.fileName.toString() + ".lock")
        val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val acquired = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        if (acquired == null) {
            channel.close()
            throw IllegalStateException("Guardian database is already in use: $path")
        }
        fileLockChannel = channel
        databaseFileLock = acquired
    }

    private fun releaseDatabaseFileLock() {
        runCatching { databaseFileLock?.release() }
        runCatching { fileLockChannel?.close() }
        databaseFileLock = null
        fileLockChannel = null
    }

    private fun clearMappingCaches() {
        worldMappings.clear()
        resourceMappings.clear()
        blockDataMappings.clear()
        actorCodec.clear()
    }

    private fun requireConnection(): Connection = checkNotNull(connection) { "Storage backend $id is not open" }

    private fun setPayload(statement: PreparedStatement, index: Int, payload: BinaryPayload?) {
        if (payload == null) statement.setNull(index, java.sql.Types.BLOB)
        else statement.setBytes(index, payload.copyBytes())
    }

    protected data class IntegrityResult(val performed: Boolean, val passed: Boolean)

    private companion object {
        val BLOCK_INSERT_SQL = """
            INSERT INTO ex_block(
                rowid, event_uuid, time, actor, wid, x, y, z,
                before_type, before_data, before_meta,
                after_type, after_data, after_meta,
                cause, action, rolled_back
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(event_uuid) DO NOTHING
        """.trimIndent()

        const val META_CLEAN_SHUTDOWN = "clean_shutdown"
        const val META_SCHEMA_VERSION = "schema_version"
        const val META_BLOCK_PROPERTIES_CODEC = "block_properties_codec"
        const val META_STORAGE_FORMAT = "storage_format"
        const val STORAGE_FORMAT = "exprotect-native-v1"
    }
}
