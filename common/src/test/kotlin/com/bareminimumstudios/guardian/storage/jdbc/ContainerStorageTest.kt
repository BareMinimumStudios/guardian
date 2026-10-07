package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.QueryableStorageBackend
import com.bareminimumstudios.guardian.storage.query.*
import com.bareminimumstudios.guardian.storage.codec.ContainerChangesCodec
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

class ContainerStorageTest {
    private val actor = ActorIdentity.Player(UUID.randomUUID(), "Tester")
    private val dimension = ResourceId.parse("minecraft:overworld")
    private val left = ItemSlotOwner.BlockContainer(dimension, BlockPosition(1, 64, 2))
    private val right = ItemSlotOwner.BlockContainer(dimension, BlockPosition(2, 64, 2))
    private val item = ItemStackSnapshot(ResourceId.parse("minecraft:diamond"), 8, BinaryPayload.of(byteArrayOf(1, 2, 3)))
    private fun transaction(id: UUID = UUID.randomUUID()) = ContainerTransactionSnapshot(id, 100, actor, 1, ContainerAction.QUICK_MOVE, listOf(
        ItemSlotChange(ItemSlotAddress(left, 0), item, ItemStackSnapshot.EMPTY),
        ItemSlotChange(ItemSlotAddress(ItemSlotOwner.PlayerInventory(actor.uuid), 9), ItemStackSnapshot.EMPTY, item)
    ), listOf(left, right))
    private fun backends(check: ((Path) -> QueryableStorageBackend, Path, String) -> Unit) {
        val folder = Files.createTempDirectory("guardian-container-tests")
        check(::SqliteStorageBackend, folder.resolve("audit.sqlite"), "jdbc:sqlite:")
        check(::DuckDbStorageBackend, folder.resolve("audit.duckdb"), "jdbc:duckdb:")
    }
    @Test fun wholeTransactionSurvivesRetryRestartAndQueries() = backends { factory, path, _ ->
        val value = transaction()
        factory(path).use { backend ->
            backend.open(); backend.append(listOf(ContainerAuditEntry(value))); backend.append(listOf(ContainerAuditEntry(value)))
            val rows = backend.lookupContainers(ContainerLookupQuery(dimension, right.position, actor.uuid))
            assertEquals(1, rows.size); assertEquals(value.transactionId, rows.single().transactionId)
            assertEquals(value.changes, rows.single().changes); assertEquals(setOf(left, right), rows.single().containers.toSet())
            assertTrue(backend.lookupContainers(ContainerLookupQuery(ResourceId.parse("minecraft:the_nether"))).isEmpty())
            assertTrue(backend.lookupContainers(ContainerLookupQuery(actorUuid = UUID.randomUUID())).isEmpty())
        }
        factory(path).use { backend -> backend.open(); assertEquals(value.changes, backend.lookupContainers(ContainerLookupQuery()).single().changes) }
    }
    @Test fun duplicateIdCannotAddDifferentLocations() = backends { factory, path, _ ->
        val value = transaction()
        factory(path).use { backend ->
            backend.open(); backend.append(listOf(ContainerAuditEntry(value)))
            val elsewhere = ItemSlotOwner.BlockContainer(dimension, BlockPosition(99, 64, 99))
            val duplicate = ContainerTransactionSnapshot(value.transactionId, 100, actor, 1, ContainerAction.CLONE, value.changes, listOf(elsewhere))
            backend.append(listOf(ContainerAuditEntry(duplicate)))
            assertTrue(backend.lookupContainers(ContainerLookupQuery(dimension, elsewhere.position)).isEmpty())
        }
    }
    @Test fun invalidTransactionRollsBackMixedBatchAndRetryWorks() = backends { factory, path, _ ->
        val valid = transaction()
        val huge = item.copy(itemData = BinaryPayload.of(ByteArray(1024 * 1024 + 1)))
        val invalid = ContainerTransactionSnapshot(UUID.randomUUID(), 101, actor, 1, ContainerAction.PICKUP,
            listOf(ItemSlotChange(ItemSlotAddress(left, 1), ItemStackSnapshot.EMPTY, huge)))
        val block = BlockChangeSnapshot(100, actor, dimension, left.position,
            BlockStateSnapshot(ResourceId.parse("minecraft:air")), BlockStateSnapshot(ResourceId.parse("minecraft:stone")), ChangeCause.PLAYER, ActionType.BLOCK_PLACE)
        factory(path).use { backend ->
            backend.open()
            assertFailsWith<IllegalArgumentException> { backend.append(listOf(block, ContainerAuditEntry(valid), ContainerAuditEntry(invalid))) }
            assertTrue(backend.lookupContainers(ContainerLookupQuery()).isEmpty()); assertTrue(backend.lookupBlocks(BlockLookupQuery()).isEmpty())
            backend.append(listOf(block, ContainerAuditEntry(valid)))
            assertEquals(1, backend.lookupContainers(ContainerLookupQuery()).size); assertEquals(1, backend.lookupBlocks(BlockLookupQuery()).size)
        }
    }
    @Test fun cursorOnlyActionRemainsLinkedToItsContainer() = backends { factory, path, _ ->
        val value = ContainerTransactionSnapshot(UUID.randomUUID(), 1, actor, 1, ContainerAction.CLONE,
            listOf(ItemSlotChange(ItemSlotAddress(ItemSlotOwner.Cursor(actor.uuid), 0), ItemStackSnapshot.EMPTY, item)), listOf(left))
        factory(path).use { backend -> backend.open(); backend.append(listOf(ContainerAuditEntry(value)))
            assertEquals(value.transactionId, backend.lookupContainers(ContainerLookupQuery(dimension, left.position)).single().transactionId)
        }
    }
    @Test fun upgradesVersionOneWithoutLosingBlockHistory() = backends { factory, path, prefix ->
        Class.forName(if (prefix.contains("sqlite")) "org.sqlite.JDBC" else "org.duckdb.DuckDBDriver")
        DriverManager.getConnection(prefix + path.toAbsolutePath()).use { conn ->
            assertEquals(1, SchemaMigrator(GuardianSchema.migrations.take(1)).migrate(conn))
            conn.createStatement().use { statement -> statement.executeUpdate("INSERT INTO ex_meta(meta_key, meta_value) VALUES ('test_preserved', 'yes')")
                statement.executeUpdate("INSERT INTO ex_world_map VALUES (1, 'minecraft:overworld')")
                statement.executeUpdate("INSERT INTO ex_resource_map VALUES (1, 'minecraft:air'), (2, 'minecraft:stone')")
                conn.prepareStatement("INSERT INTO ex_blockdata_map VALUES (1, ?)").use { data -> data.setString(1, com.bareminimumstudios.guardian.storage.codec.BlockPropertiesCodec.encode(emptyMap())); data.executeUpdate() }
                statement.executeUpdate("INSERT INTO ex_block VALUES (1, '${UUID.randomUUID()}', 100, NULL, 1, 1, 64, 2, 1, 1, NULL, 2, 1, NULL, 1, 1, 0)") }
        }
        factory(path).use { backend -> backend.open(); assertEquals(2, backend.health().schemaVersion); assertEquals(ResourceId.parse("minecraft:stone"), backend.lookupBlocks(BlockLookupQuery()).single().snapshot.after.blockId); backend.append(listOf(ContainerAuditEntry(transaction()))) }
        DriverManager.getConnection(prefix + path.toAbsolutePath()).use { conn ->
            conn.createStatement().use { statement -> statement.executeQuery("SELECT meta_value FROM ex_meta WHERE meta_key = 'test_preserved'").use { rows -> assertTrue(rows.next()); assertEquals("yes", rows.getString(1)) } }
        }
    }
    @Test fun encodingRejectsCorruptionAndTrailingBytes() {
        val value = transaction(); val bytes = ContainerChangesCodec.encode(value.changes)
        assertEquals(value.changes, ContainerChangesCodec.decode(bytes))
        assertFailsWith<IllegalArgumentException> { ContainerChangesCodec.decode(bytes + byteArrayOf(1)) }
        bytes[0] = 0
        assertFailsWith<IllegalArgumentException> { ContainerChangesCodec.decode(bytes) }
        assertFailsWith<java.io.EOFException> { ContainerChangesCodec.decode(byteArrayOf(0x47, 0x43, 0x54, 0x31)) }
    }
}
