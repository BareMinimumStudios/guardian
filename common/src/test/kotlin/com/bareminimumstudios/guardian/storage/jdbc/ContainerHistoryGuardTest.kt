package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.*
import com.bareminimumstudios.guardian.storage.codec.ContainerChangesCodec
import com.bareminimumstudios.guardian.storage.query.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

class ContainerHistoryGuardTest {
    private val dimension=ResourceId.parse("minecraft:overworld")
    private val left=ItemSlotAddress(ItemSlotOwner.BlockContainer(dimension,BlockPosition(1,64,1)),0)
    private val right=ItemSlotAddress(ItemSlotOwner.BlockContainer(dimension,BlockPosition(2,64,1)),0)
    private val item=ItemStackSnapshot(ResourceId.parse("minecraft:coal"),1,BinaryPayload.of(byteArrayOf(1)))
    private fun row(time: Long=100, source: ItemSlotAddress=left, destination: ItemSlotAddress=right, id: UUID=UUID.randomUUID()) = ContainerTransactionSnapshot(id,time,ActorIdentity.System("hopper"),0,ContainerAction.HOPPER_TRANSFER,listOf(ItemSlotChange(source,item,ItemStackSnapshot.EMPTY),ItemSlotChange(destination,ItemStackSnapshot.EMPTY,item)))
    private fun backends(test: ((Path)->JdbcStorageBackend,Path,String)->Unit) {
        val folder=Files.createTempDirectory("guardian-history-guard")
        test(::SqliteStorageBackend,folder.resolve("audit.sqlite"),"jdbc:sqlite:")
        test(::DuckDbStorageBackend,folder.resolve("audit.duckdb"),"jdbc:duckdb:")
    }
    @Test fun excludedNewerInventoryHistoryBlocksPreviewAndPreparation() = backends { factory,path,_ ->
        val first=row();val newer=row(101,right,left)
        factory(path).use { db ->
            db.open();db.append(listOf(ContainerAuditEntry(first),ContainerAuditEntry(newer)))
            val guard=db.guardContainerHistory(listOf(first));assertEquals(setOf(left.owner,right.owner),guard.newerOwners)
            val live=InventorySnapshot(first.changes.associate { it.address to it.after })
            assertEquals(ContainerPreviewReason.NEWER_HISTORY,ContainerRollbackPlanner.plan(listOf(first),live,historyGuard=guard).entries.single().reason)
            val id=UUID.randomUUID();assertFailsWith<IllegalArgumentException> { db.prepareItemRollback(id,200,listOf(first)) };assertNull(db.itemRollback(id))
            assertTrue(db.guardContainerHistory(listOf(newer,first)).clear)
        }
    }
    @Test fun equallyTimedExcludedRecordBlocksEvenIfItsSlotsDiffer() = backends { factory,path,_ ->
        val first=row();val same=row(100,left.copy(index=1),right.copy(index=1))
        factory(path).use { db -> db.open();db.append(listOf(ContainerAuditEntry(first),ContainerAuditEntry(same)));assertFalse(db.guardContainerHistory(listOf(first)).clear) }
    }
    @Test fun olderAndIndependentInventoriesDoNotInvalidateCandidate() = backends { factory,path,_ ->
        val newest=row(101);val older=row(99)
        val elsewhere=ItemSlotAddress(ItemSlotOwner.BlockContainer(dimension,BlockPosition(10,64,1)),0)
        val independent=row(102,elsewhere,elsewhere.copy(index=1))
        factory(path).use { db -> db.open();db.append(listOf(ContainerAuditEntry(newest),ContainerAuditEntry(older),ContainerAuditEntry(independent)));assertTrue(db.guardContainerHistory(listOf(newest)).clear) }
    }
    @Test fun blockHistoryAtOrAfterSourceInvalidatesPositionRegardlessOfRollbackState() = backends { factory,path,_ ->
        val value=row()
        val change=BlockChangeSnapshot(100,ActorIdentity.Unknown,dimension,BlockPosition(1,64,1),BlockStateSnapshot(ResourceId.parse("minecraft:barrel")),BlockStateSnapshot(ResourceId.parse("minecraft:air")),ChangeCause.PLAYER,ActionType.BLOCK_BREAK)
        factory(path).use { db ->
            db.open();db.append(listOf(ContainerAuditEntry(value),change))
            val block=db.lookupBlocks(BlockLookupQuery()).single();db.setBlockRollbackState(listOf(block.rowId),BlockRollbackState.ROLLED_BACK)
            val guard=db.guardContainerHistory(listOf(value));assertEquals(setOf(left.owner),guard.changedBlocks)
            val live=InventorySnapshot(value.changes.associate { it.address to it.after })
            assertEquals(ContainerPreviewReason.CHANGED_BLOCK,ContainerRollbackPlanner.plan(listOf(value),live,historyGuard=guard).entries.single().reason)
            assertFailsWith<IllegalArgumentException> { db.prepareItemRollback(UUID.randomUUID(),200,listOf(value)) }
        }
    }
    @Test fun unrelatedDimensionBlockHistoryDoesNotInvalidatePosition() = backends { factory,path,_ ->
        val value=row();val change=BlockChangeSnapshot(101,ActorIdentity.Unknown,ResourceId.parse("minecraft:the_nether"),BlockPosition(1,64,1),BlockStateSnapshot(ResourceId.parse("minecraft:barrel")),BlockStateSnapshot(ResourceId.parse("minecraft:air")),ChangeCause.PLAYER,ActionType.BLOCK_BREAK)
        factory(path).use { db -> db.open();db.append(listOf(ContainerAuditEntry(value),change));assertTrue(db.guardContainerHistory(listOf(value)).clear) }
    }
    @Test fun reservedAndCompletedSourceClaimsAreVisibleToPreview() = backends { factory,path,_ ->
        val value=row();val id=UUID.randomUUID()
        factory(path).use { db ->
            db.open();db.append(listOf(ContainerAuditEntry(value)));db.prepareItemRollback(id,200,listOf(value))
            val active=db.guardContainerHistory(listOf(value));assertEquals(setOf(left.owner,right.owner),active.reservedOwners);assertEquals(setOf(value.transactionId),active.claimedTransactions)
            db.transitionItemRollback(id,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING);db.transitionItemRollback(id,ItemRollbackPhase.APPLYING,ItemRollbackPhase.COMPLETED)
            val done=db.guardContainerHistory(listOf(value));assertTrue(done.reservedOwners.isEmpty());assertEquals(setOf(value.transactionId),done.claimedTransactions)
        }
    }
    @Test fun cursorAndCraftingChangesIndexThePlayerInventoryOwner() = backends { factory,path,_ ->
        val actor=ActorIdentity.Player(UUID.randomUUID(),"Tester")
        val player=ItemSlotAddress(ItemSlotOwner.PlayerInventory(actor.uuid),0)
        val value=ContainerTransactionSnapshot(UUID.randomUUID(),100,actor,0,ContainerAction.QUICK_MOVE,listOf(ItemSlotChange(left,item,ItemStackSnapshot.EMPTY),ItemSlotChange(player,ItemStackSnapshot.EMPTY,item)))
        val cursor=ContainerTransactionSnapshot(UUID.randomUUID(),101,actor,0,ContainerAction.CREATIVE_SET,listOf(ItemSlotChange(ItemSlotAddress(ItemSlotOwner.Cursor(actor.uuid),0),ItemStackSnapshot.EMPTY,item)))
        val grid=ContainerTransactionSnapshot(UUID.randomUUID(),102,actor,0,ContainerAction.RECIPE_PLACE,listOf(ItemSlotChange(ItemSlotAddress(ItemSlotOwner.CraftingGrid(actor.uuid,0),0),ItemStackSnapshot.EMPTY,item)))
        factory(path).use { db -> db.open();db.append(listOf(ContainerAuditEntry(value),ContainerAuditEntry(cursor),ContainerAuditEntry(grid)));assertEquals(setOf(player.owner),db.guardContainerHistory(listOf(value)).newerOwners) }
    }
    @Test fun duplicateRetryCannotAddFakeOwnersToTheIndex() = backends { factory,path,_ ->
        val value=row();val elsewhere=ItemSlotAddress(ItemSlotOwner.BlockContainer(dimension,BlockPosition(10,64,1)),0)
        val other=row(101,elsewhere,elsewhere.copy(index=1));val duplicate=row(102,left,right,id=other.transactionId)
        factory(path).use { db -> db.open();db.append(listOf(ContainerAuditEntry(value),ContainerAuditEntry(other)));db.append(listOf(ContainerAuditEntry(duplicate)));assertTrue(db.guardContainerHistory(listOf(value)).clear) }
    }
    @Test fun schemaSevenBackfillUsesMultiplePagesAndPreservesSourceBytes() = backends { factory,path,prefix ->
        Class.forName(if(prefix.contains("sqlite")) "org.sqlite.JDBC" else "org.duckdb.DuckDBDriver")
        val values=(1..260).map { row(it.toLong(),id=UUID(0,it.toLong())) }
        DriverManager.getConnection(prefix+path.toAbsolutePath()).use { conn ->
            assertEquals(7,SchemaMigrator(GuardianSchema.migrations.take(7)).migrate(conn))
            conn.prepareStatement("INSERT INTO ex_container VALUES (?,?,1,0,'HOPPER_TRANSFER',?)").use { insert -> values.forEach { value -> insert.setString(1,value.transactionId.toString());insert.setLong(2,value.timestampEpochMillis);insert.setBytes(3,ContainerChangesCodec.encode(value.changes));insert.addBatch() };insert.executeBatch() }
        }
        factory(path).use { db -> db.open();assertEquals(GuardianSchema.CURRENT_VERSION,db.health().schemaVersion);assertTrue(db.guardContainerHistory(listOf(values.last())).clear);assertFalse(db.guardContainerHistory(listOf(values.first())).clear) }
        DriverManager.getConnection(prefix+path.toAbsolutePath()).use { conn ->
            conn.createStatement().use { statement -> statement.executeQuery("SELECT COUNT(*) FROM ex_container_owner").use { result -> assertTrue(result.next());assertEquals(520,result.getInt(1)) } }
            conn.prepareStatement("SELECT changes FROM ex_container WHERE transaction_uuid=?").use { query -> query.setString(1,values.first().transactionId.toString());query.executeQuery().use { result -> assertTrue(result.next());assertContentEquals(ContainerChangesCodec.encode(values.first().changes),result.getBytes(1)) } }
            assertFailsWith<IllegalArgumentException> { SchemaMigrator(GuardianSchema.migrations.take(7)).migrate(conn) }
        }
    }
    @Test fun corruptHistoricalPayloadAbortsMigrationAtomically() = backends { factory,path,prefix ->
        Class.forName(if(prefix.contains("sqlite")) "org.sqlite.JDBC" else "org.duckdb.DuckDBDriver")
        DriverManager.getConnection(prefix+path.toAbsolutePath()).use { conn ->
            SchemaMigrator(GuardianSchema.migrations.take(7)).migrate(conn)
            conn.prepareStatement("INSERT INTO ex_container VALUES (?,100,1,0,'HOPPER_TRANSFER',?)").use { insert -> insert.setString(1,UUID.randomUUID().toString());insert.setBytes(2,byteArrayOf(1));insert.executeUpdate() }
            assertFails { SchemaMigrator().migrate(conn) }
            conn.createStatement().use { statement -> statement.executeQuery("SELECT MAX(version) FROM ex_schema_migrations").use { result -> assertTrue(result.next());assertEquals(7,result.getInt(1)) } }
        }
    }
}
