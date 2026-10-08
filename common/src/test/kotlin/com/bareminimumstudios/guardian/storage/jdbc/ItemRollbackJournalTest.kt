package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.*
import com.bareminimumstudios.guardian.storage.query.ContainerLookupQuery
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

class ItemRollbackJournalTest {
    private val dimension=ResourceId.parse("minecraft:overworld")
    private val left=ItemSlotAddress(ItemSlotOwner.BlockContainer(dimension,BlockPosition(1,64,1)),0)
    private val right=ItemSlotAddress(ItemSlotOwner.BlockContainer(dimension,BlockPosition(2,64,1)),0)
    private val item=ItemStackSnapshot(ResourceId.parse("minecraft:diamond"),3,BinaryPayload.of(byteArrayOf(1,2,3)))
    private fun row(time: Long=100, source: ItemSlotAddress=left, destination: ItemSlotAddress=right, value: ItemStackSnapshot=item, id: UUID=UUID.randomUUID()) = ContainerTransactionSnapshot(id,time,ActorIdentity.System("hopper"),0,ContainerAction.HOPPER_TRANSFER,listOf(
        ItemSlotChange(source,value,ItemStackSnapshot.EMPTY),ItemSlotChange(destination,ItemStackSnapshot.EMPTY,value)))
    private fun backends(test: ((Path)->JdbcStorageBackend,Path,String)->Unit) {
        val folder=Files.createTempDirectory("guardian-item-journal")
        test(::SqliteStorageBackend,folder.resolve("audit.sqlite"),"jdbc:sqlite:")
        test(::DuckDbStorageBackend,folder.resolve("audit.duckdb"),"jdbc:duckdb:")
    }
    @Test fun preparedPlanIsImmutableIdempotentAndSurvivesRestart() = backends { factory,path,_ ->
        val value=row();val id=UUID.randomUUID();val mutable=mutableListOf(value)
        factory(path).use { db ->
            db.open();db.append(listOf(ContainerAuditEntry(value)))
            val record=db.prepareItemRollback(id,200,mutable);mutable.clear()
            assertEquals(value.changes,record.entries.single().changes)
            assertEquals(id,db.prepareItemRollback(id,200,listOf(value)).operationId)
            assertEquals(1,db.unfinishedItemRollbacks().size)
            assertEquals(1,db.health().unfinishedItemRollbacks)
            assertFailsWith<UnsupportedOperationException> { (record.entries as MutableList).clear() }
            assertFailsWith<IllegalArgumentException> { db.prepareItemRollback(id,201,listOf(value)) }
        }
        factory(path).use { db -> db.open();assertEquals(ItemRollbackPhase.PREPARED,db.itemRollback(id)?.phase);assertEquals(value.changes,db.itemRollback(id)?.entries?.single()?.changes) }
    }
    @Test fun overlappingOwnersFailAtomicallyAndPreparedCancellationReleasesClaims() = backends { factory,path,_ ->
        val first=row(101);val second=row();val a=UUID.randomUUID();val b=UUID.randomUUID()
        factory(path).use { db ->
            db.open();db.append(listOf(ContainerAuditEntry(first),ContainerAuditEntry(second)));db.prepareItemRollback(a,200,listOf(first))
            assertFails { db.prepareItemRollback(b,201,listOf(second)) };assertNull(db.itemRollback(b));assertEquals(1,db.unfinishedItemRollbacks().size)
            assertTrue(db.transitionItemRollback(a,ItemRollbackPhase.PREPARED,ItemRollbackPhase.CANCELLED))
            assertEquals(0,db.health().unfinishedItemRollbacks)
            db.prepareItemRollback(b,201,listOf(first));assertEquals(ItemRollbackPhase.PREPARED,db.itemRollback(b)?.phase)
        }
    }
    @Test fun applyingSurvivesRestartAsRecoveryRequiredWithOwnerClaimsHeld() = backends { factory,path,_ ->
        val value=row(101);val next=row();val id=UUID.randomUUID()
        factory(path).use { db -> db.open();db.append(listOf(ContainerAuditEntry(value),ContainerAuditEntry(next)));db.prepareItemRollback(id,200,listOf(value));assertTrue(db.transitionItemRollback(id,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING)) }
        factory(path).use { db ->
            db.open();assertEquals(ItemRollbackPhase.RECOVERY_REQUIRED,db.itemRollback(id)?.phase)
            assertFalse(db.transitionItemRollback(id,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING))
            assertFails { db.prepareItemRollback(UUID.randomUUID(),201,listOf(next)) }
            assertFailsWith<IllegalArgumentException> { db.transitionItemRollback(id,ItemRollbackPhase.RECOVERY_REQUIRED,ItemRollbackPhase.CANCELLED) }
            assertEquals(value.changes,db.lookupContainers(ContainerLookupQuery()).first { it.transactionId==value.transactionId }.changes)
        }
    }
    @Test fun completedSourceCannotBeReusedButOwnersAreReleased() = backends { factory,path,_ ->
        val first=row();val second=row(101);val id=UUID.randomUUID()
        factory(path).use { db ->
            db.open();db.append(listOf(ContainerAuditEntry(first)));db.prepareItemRollback(id,200,listOf(first))
            assertFailsWith<IllegalArgumentException> { db.transitionItemRollback(id,ItemRollbackPhase.PREPARED,ItemRollbackPhase.COMPLETED) }
            assertTrue(db.transitionItemRollback(id,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING))
            assertTrue(db.transitionItemRollback(id,ItemRollbackPhase.APPLYING,ItemRollbackPhase.COMPLETED))
            val retry=UUID.randomUUID();assertFails { db.prepareItemRollback(retry,201,listOf(first)) };assertNull(db.itemRollback(retry))
            db.append(listOf(ContainerAuditEntry(second)))
            db.prepareItemRollback(UUID.randomUUID(),202,listOf(second));assertEquals(1,db.unfinishedItemRollbacks().size)
        }
    }
    @Test fun sourceProvenanceAndBudgetsRejectBeforeCommitting() = backends { factory,path,_ ->
        val value=row()
        factory(path).use { db ->
            db.open();assertFailsWith<IllegalArgumentException> { db.prepareItemRollback(UUID.randomUUID(),200,listOf(value)) }
            db.append(listOf(ContainerAuditEntry(value)))
            assertFailsWith<IllegalArgumentException> { db.prepareItemRollback(UUID.randomUUID(),200,listOf(row(value=value.changes[0].before.copy(count=4),id=value.transactionId))) }
            assertFailsWith<IllegalArgumentException> { db.prepareItemRollback(UUID.randomUUID(),200,emptyList()) }
            assertFailsWith<IllegalArgumentException> { db.prepareItemRollback(UUID.randomUUID(),200,List(51) { row(it.toLong()) }.reversed()) }
            assertFailsWith<IllegalArgumentException> { db.unfinishedItemRollbacks(101) }
            assertTrue(db.unfinishedItemRollbacks().isEmpty())
        }
    }
    @Test fun reverseChainAndRecoveryClassificationKeepExactComponents() = backends { factory,path,_ ->
        val third=ItemSlotAddress(ItemSlotOwner.BlockContainer(dimension,BlockPosition(3,64,1)),0)
        val first=row();val second=row(101,right,third);val id=UUID.randomUUID()
        factory(path).use { db ->
            db.open();db.append(listOf(ContainerAuditEntry(first),ContainerAuditEntry(second)))
            val record=db.prepareItemRollback(id,200,listOf(second,first))
            assertEquals(listOf(second.transactionId,first.transactionId),record.entries.map { it.transactionId })
            fun observe(a: ItemStackSnapshot,b: ItemStackSnapshot,c: ItemStackSnapshot)=ItemRollbackRecovery.observe(record,InventorySnapshot(mapOf(left to a,right to b,third to c)))
            assertEquals(ItemRecoveryObservation.ORIGINAL,observe(ItemStackSnapshot.EMPTY,ItemStackSnapshot.EMPTY,item))
            assertEquals(ItemRecoveryObservation.RESTORED,observe(item,ItemStackSnapshot.EMPTY,ItemStackSnapshot.EMPTY))
            assertEquals(ItemRecoveryObservation.PARTIAL,observe(item,ItemStackSnapshot.EMPTY,item))
            assertEquals(ItemRecoveryObservation.CONFLICT,observe(item.copy(itemData=BinaryPayload.of(byteArrayOf(9))),ItemStackSnapshot.EMPTY,ItemStackSnapshot.EMPTY))
            assertEquals(ItemRecoveryObservation.UNAVAILABLE,ItemRollbackRecovery.observe(record,InventorySnapshot(emptyMap())))
            assertEquals(ItemRollbackPhase.PREPARED,db.itemRollback(id)?.phase)
        }
    }
    @Test fun cyclicPlanHasAmbiguousOriginalAndRestoredObservation() = backends { factory,path,_ ->
        val first=row();val second=row(101,right,left)
        factory(path).use { db ->
            db.open();db.append(listOf(ContainerAuditEntry(first),ContainerAuditEntry(second)))
            val record=db.prepareItemRollback(UUID.randomUUID(),200,listOf(second,first))
            assertEquals(ItemRecoveryObservation.BOTH,ItemRollbackRecovery.observe(record,InventorySnapshot(mapOf(left to item,right to ItemStackSnapshot.EMPTY))))
        }
    }
    @Test fun schemaSixMigratesWithoutChangingAuditAndRejectsOlderReader() = backends { factory,path,prefix ->
        Class.forName(if(prefix.contains("sqlite")) "org.sqlite.JDBC" else "org.duckdb.DuckDBDriver")
        DriverManager.getConnection(prefix+path.toAbsolutePath()).use { conn -> assertEquals(6,SchemaMigrator(GuardianSchema.migrations.take(6)).migrate(conn)) }
        factory(path).use { db -> db.open();assertEquals(GuardianSchema.CURRENT_VERSION,db.health().schemaVersion);assertTrue(db.unfinishedItemRollbacks().isEmpty()) }
        DriverManager.getConnection(prefix+path.toAbsolutePath()).use { conn -> assertFailsWith<IllegalArgumentException> { SchemaMigrator(GuardianSchema.migrations.take(6)).migrate(conn) } }
    }
    @Test fun committedApplyIntentSurvivesAbruptChildProcessExit() = backends { factory,path,prefix ->
        val value=row();val id=UUID.randomUUID()
        factory(path).use { db -> db.open();db.append(listOf(ContainerAuditEntry(value)));db.prepareItemRollback(id,200,listOf(value)) }
        val driver=if(prefix.contains("sqlite")) "org.sqlite.JDBC" else "org.duckdb.DuckDBDriver"
        val classes=listOf(ItemJournalCrashWriter::class.java,Class.forName(driver),Class.forName("org.slf4j.LoggerFactory"))
        val classpath=classes.map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(java.io.File.pathSeparator)
        val process=ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",classpath,ItemJournalCrashWriter::class.java.name,driver,prefix+path.toAbsolutePath(),id.toString()).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS))
            val output=process.inputStream.bufferedReader().readText()
            assertEquals(23,process.exitValue(),output);assertContains(output,"APPLY_INTENT_COMMITTED")
        } finally { if(process.isAlive) process.destroyForcibly() }
        factory(path).use { db ->
            db.open();assertEquals(ItemRollbackPhase.RECOVERY_REQUIRED,db.itemRollback(id)?.phase)
            assertEquals(1,db.health().unfinishedItemRollbacks)
            assertEquals(value.changes,db.lookupContainers(ContainerLookupQuery()).single().changes)
        }
    }

}
