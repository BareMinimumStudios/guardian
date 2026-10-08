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
            val duplicate = ContainerTransactionSnapshot(value.transactionId, 100, actor, 1, ContainerAction.CLONE, listOf(ItemSlotChange(ItemSlotAddress(elsewhere, 0), ItemStackSnapshot.EMPTY, item)), listOf(elsewhere))
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
        factory(path).use { backend -> backend.open(); assertEquals(GuardianSchema.CURRENT_VERSION, backend.health().schemaVersion); assertEquals(ResourceId.parse("minecraft:stone"), backend.lookupBlocks(BlockLookupQuery()).single().snapshot.after.blockId); backend.append(listOf(ContainerAuditEntry(transaction()))) }
        DriverManager.getConnection(prefix + path.toAbsolutePath()).use { conn ->
            conn.createStatement().use { statement -> statement.executeQuery("SELECT meta_value FROM ex_meta WHERE meta_key = 'test_preserved'").use { rows -> assertTrue(rows.next()); assertEquals("yes", rows.getString(1)) } }
        }
    }
    @Test fun standaloneActionKindsPersistAndCanBeQueriedByPlayerName() = backends { factory, path, _ ->
        factory(path).use { backend ->
            backend.open()
            val records = listOf(ContainerAction.DROP_ONE, ContainerAction.DROP_STACK, ContainerAction.SWAP_OFFHAND, ContainerAction.CREATIVE_SET).mapIndexed { index, action ->
                ContainerAuditEntry(ContainerTransactionSnapshot(UUID.randomUUID(), index.toLong(), actor, 0, action,
                    listOf(ItemSlotChange(ItemSlotAddress(ItemSlotOwner.PlayerInventory(actor.uuid), 0), item, ItemStackSnapshot.EMPTY))))
            }
            backend.append(records)
            val rows = backend.lookupContainers(ContainerLookupQuery(actorName = "TESTER"))
            assertEquals(listOf(ContainerAction.CREATIVE_SET, ContainerAction.SWAP_OFFHAND, ContainerAction.DROP_STACK, ContainerAction.DROP_ONE), rows.map { it.action })
            assertTrue(rows.all { it.containers.isEmpty() })
            assertTrue(backend.lookupContainers(ContainerLookupQuery(dimension, left.position)).isEmpty())
            assertTrue(backend.lookupContainers(ContainerLookupQuery(actorName = "Other")).isEmpty())
        }
        factory(path).use { backend -> backend.open(); assertEquals(4, backend.lookupContainers(ContainerLookupQuery(actorUuid = actor.uuid)).size) }
    }
    @Test fun upgradesVersionTwoAndRejectsOlderReaders() = backends { factory, path, prefix ->
        Class.forName(if (prefix.contains("sqlite")) "org.sqlite.JDBC" else "org.duckdb.DuckDBDriver")
        DriverManager.getConnection(prefix + path.toAbsolutePath()).use { conn -> assertEquals(2, SchemaMigrator(GuardianSchema.migrations.take(2)).migrate(conn)) }
        factory(path).use { backend -> backend.open(); assertEquals(GuardianSchema.CURRENT_VERSION, backend.health().schemaVersion) }
        DriverManager.getConnection(prefix + path.toAbsolutePath()).use { conn ->
            assertFailsWith<IllegalArgumentException> { SchemaMigrator(GuardianSchema.migrations.take(2)).migrate(conn) }
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
    @Test fun upgradesVersionThreeAndRejectsOldCreativeReaders() = backends { factory, path, prefix ->
        Class.forName(if (prefix.contains("sqlite")) "org.sqlite.JDBC" else "org.duckdb.DuckDBDriver")
        DriverManager.getConnection(prefix + path.toAbsolutePath()).use { conn ->
            assertEquals(3, SchemaMigrator(GuardianSchema.migrations.take(3)).migrate(conn))
        }
        factory(path).use { backend ->
            backend.open(); assertEquals(GuardianSchema.CURRENT_VERSION, backend.health().schemaVersion)
            val value = ContainerTransactionSnapshot(UUID.randomUUID(), 1, actor, 0, ContainerAction.CREATIVE_SET,
                listOf(ItemSlotChange(ItemSlotAddress(ItemSlotOwner.PlayerInventory(actor.uuid), 0), ItemStackSnapshot.EMPTY, item)))
            backend.append(listOf(ContainerAuditEntry(value)))
        }
        DriverManager.getConnection(prefix + path.toAbsolutePath()).use { conn ->
            assertFailsWith<IllegalArgumentException> { SchemaMigrator(GuardianSchema.migrations.take(3)).migrate(conn) }
        }
        factory(path).use { backend -> backend.open(); assertEquals(ContainerAction.CREATIVE_SET, backend.lookupContainers(ContainerLookupQuery()).single().action) }
    }

    @Test fun systemTransferPersistsDeduplicatesAndStaysOutOfPlayerQueries() = backends { factory, path, _ ->
        val value = ContainerTransactionSnapshot(UUID.randomUUID(), 1, ActorIdentity.System("minecraft:hopper"), 0, ContainerAction.HOPPER_TRANSFER,
            listOf(ItemSlotChange(ItemSlotAddress(left, 0), item, ItemStackSnapshot.EMPTY), ItemSlotChange(ItemSlotAddress(right, 0), ItemStackSnapshot.EMPTY, item)))
        factory(path).use { backend ->
            backend.open(); backend.append(listOf(ContainerAuditEntry(value), ContainerAuditEntry(transaction())))
            backend.append(listOf(ContainerAuditEntry(value)))
            assertEquals(2, backend.lookupContainers(ContainerLookupQuery(dimension, left.position)).size)
            assertEquals(1, backend.lookupContainers(ContainerLookupQuery(actorName = "Tester")).size)
            assertEquals(1, backend.lookupContainers(ContainerLookupQuery(actorUuid = actor.uuid)).size)
        }
        factory(path).use { backend ->
            backend.open()
            val stored = backend.lookupContainers(ContainerLookupQuery()).single { it.transactionId == value.transactionId }
            assertEquals(value.actor, stored.actor); assertEquals(value.changes, stored.changes); assertEquals(value.containers.toSet(), stored.containers.toSet())
        }
    }
    @Test fun upgradesVersionFourForSystemTransactions() = backends { factory, path, prefix ->
        Class.forName(if (prefix.contains("sqlite")) "org.sqlite.JDBC" else "org.duckdb.DuckDBDriver")
        DriverManager.getConnection(prefix + path.toAbsolutePath()).use { conn -> assertEquals(4, SchemaMigrator(GuardianSchema.migrations.take(4)).migrate(conn)) }
        factory(path).use { backend -> backend.open(); assertEquals(GuardianSchema.CURRENT_VERSION, backend.health().schemaVersion) }
        DriverManager.getConnection(prefix + path.toAbsolutePath()).use { conn ->
            assertFailsWith<IllegalArgumentException> { SchemaMigrator(GuardianSchema.migrations.take(4)).migrate(conn) }
        }
    }

    @Test fun timeRadiusAndSelectionMatchOnBothDatabases() = backends { factory, path, _ ->
        factory(path).use { backend ->
            backend.open(); backend.append(listOf(ContainerAuditEntry(transaction())))
            val near = ContainerLookupQuery(dimension, BlockPosition(0, 64, 2), radius = 2, afterEpochMillis = 100, actorName = "TESTER")
            assertTrue(near.matches(transaction()))
            assertEquals(1, backend.lookupContainers(near).size)
            assertTrue(backend.lookupContainers(near.copy(afterEpochMillis = 101)).isEmpty())
            assertTrue(backend.lookupContainers(near.copy(radius = 0)).isEmpty())
            val selection = ContainerLookupQuery(dimension = dimension, bounds = BlockBounds(right.position, right.position))
            assertTrue(selection.matches(transaction()))
            assertEquals(1, backend.lookupContainers(selection).size)
        }
    }
    @Test fun playerUuidFindsRenamedPlayerAndModdedBlocks() = backends { factory, path, _ ->
        factory(path).use { backend ->
            backend.open()
            val old = BlockChangeSnapshot(100, actor, dimension, left.position,
                BlockStateSnapshot(ResourceId.parse("minecraft:air")), BlockStateSnapshot(ResourceId.parse("example:machine")), ChangeCause.PLAYER, ActionType.BLOCK_PLACE)
            backend.append(listOf(old))
            backend.append(listOf(BlockChangeSnapshot(101, actor.copy(lastKnownName = "Renamed"), dimension, right.position,
                old.before, old.after, ChangeCause.PLAYER, ActionType.BLOCK_PLACE)))
            val rows = backend.lookupBlocks(BlockLookupQuery(actorUuid = actor.uuid))
            assertEquals(2, rows.size)
            assertTrue(rows.all { it.snapshot.after.blockId == ResourceId.parse("example:machine") })
            assertEquals(2, backend.lookupBlocks(BlockLookupQuery(actorName = "renamed")).size)
        }
    }

    @Test fun paginationReturnsOlderBlockAndItemRecordsWithoutDuplicates() = backends { factory, path, _ ->
        factory(path).use { backend ->
            backend.open()
            backend.append(listOf(ContainerAuditEntry(transaction()), ContainerAuditEntry(transaction())))
            val query = ContainerLookupQuery(actorName = "Tester", limit = 1)
            val first = backend.lookupContainers(query).single()
            val second = backend.lookupContainers(query.copy(offset = 1)).single()
            assertNotEquals(first.transactionId, second.transactionId)
            assertTrue(backend.lookupContainers(query.copy(offset = 2)).isEmpty())
            val block = BlockChangeSnapshot(100, actor, dimension, left.position,
                BlockStateSnapshot(ResourceId.parse("minecraft:air")), BlockStateSnapshot(ResourceId.parse("example:block")), ChangeCause.PLAYER, ActionType.BLOCK_PLACE)
            backend.append(listOf(block, block.copy(eventId = UUID.randomUUID())))
            val blocks = BlockLookupQuery(actorName = "Tester", limit = 1)
            assertNotEquals(backend.lookupBlocks(blocks).single().rowId, backend.lookupBlocks(blocks.copy(offset = 1)).single().rowId)
            assertTrue(backend.lookupBlocks(blocks.copy(offset = 2)).isEmpty())
        }
    }

    @Test fun chronologicalOrderingAndPagesMatchBothBackends() = backends { factory, path, _ ->
        factory(path).use { backend ->
            backend.open()
            val first = transaction()
            val next = ContainerTransactionSnapshot(UUID.randomUUID(), 200, actor, 1, first.action, first.changes, first.containers)
            backend.append(listOf(ContainerAuditEntry(first), ContainerAuditEntry(next)))
            val query = ContainerLookupQuery(limit = 1, oldestFirst = true)
            assertEquals(first.transactionId, backend.lookupContainers(query).single().transactionId)
            assertEquals(next.transactionId, backend.lookupContainers(query.copy(offset = 1)).single().transactionId)
            assertEquals(next.transactionId, backend.lookupContainers(query.copy(oldestFirst = false)).single().transactionId)
            val block = BlockChangeSnapshot(100, actor, dimension, left.position, BlockStateSnapshot(ResourceId.parse("minecraft:air")), BlockStateSnapshot(ResourceId.parse("minecraft:stone")), ChangeCause.PLAYER, ActionType.BLOCK_PLACE)
            backend.append(listOf(block, block.copy(timestampEpochMillis = 200, eventId = UUID.randomUUID())))
            assertEquals(100, backend.lookupBlocks(BlockLookupQuery(limit = 1, oldestFirst = true)).single().snapshot.timestampEpochMillis)
            assertEquals(200, backend.lookupBlocks(BlockLookupQuery(limit = 1, oldestFirst = true, offset = 1)).single().snapshot.timestampEpochMillis)
        }
    }

    @Test fun craftingSurvivesSchemaFiveUpgradeAndOlderReaderIsRejected() = backends { factory,path,prefix ->
        Class.forName(if(prefix.contains("sqlite")) "org.sqlite.JDBC" else "org.duckdb.DuckDBDriver")
        DriverManager.getConnection(prefix+path.toAbsolutePath()).use { conn -> assertEquals(5,SchemaMigrator(GuardianSchema.migrations.take(5)).migrate(conn)) }
        val grid=ItemSlotAddress(ItemSlotOwner.CraftingGrid(actor.uuid,7),0)
        val tx=ContainerTransactionSnapshot(UUID.randomUUID(),100,actor,7,ContainerAction.CRAFT,listOf(
            ItemSlotChange(grid,item,ItemStackSnapshot.EMPTY),
            ItemSlotChange(ItemSlotAddress(ItemSlotOwner.Cursor(actor.uuid),0),ItemStackSnapshot.EMPTY,item)),listOf(left))
        factory(path).use { backend -> backend.open();backend.append(listOf(ContainerAuditEntry(tx))) }
        factory(path).use { backend -> backend.open();val row=backend.lookupContainers(ContainerLookupQuery(dimension,left.position)).single();assertEquals(tx.changes,row.changes);assertEquals(ContainerAction.CRAFT,row.action) }
        DriverManager.getConnection(prefix+path.toAbsolutePath()).use { conn -> assertFailsWith<IllegalArgumentException>{SchemaMigrator(GuardianSchema.migrations.take(5)).migrate(conn)} }
    }
}
