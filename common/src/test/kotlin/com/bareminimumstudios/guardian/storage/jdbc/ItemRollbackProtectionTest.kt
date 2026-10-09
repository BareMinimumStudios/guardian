package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.*
import com.bareminimumstudios.guardian.storage.query.ContainerLookupQuery
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.*
import kotlin.test.*

class ItemRollbackProtectionTest {
    private val dimension=ResourceId.parse("minecraft:overworld")
    private val item=ItemStackSnapshot(ResourceId.parse("minecraft:coal"),2,BinaryPayload.of(byteArrayOf(1,2)))
    private fun row(offset: Int=0,time: Long=100): ContainerTransactionSnapshot {
        val a=ItemSlotAddress(ItemSlotOwner.BlockContainer(dimension,BlockPosition(offset,64,0)),0)
        val b=ItemSlotAddress(ItemSlotOwner.BlockContainer(dimension,BlockPosition(offset+1,64,0)),0)
        return ContainerTransactionSnapshot(UUID.randomUUID(),time,ActorIdentity.System("hopper"),0,ContainerAction.HOPPER_TRANSFER,listOf(
            ItemSlotChange(a,item,ItemStackSnapshot.EMPTY),ItemSlotChange(b,ItemStackSnapshot.EMPTY,item)))
    }
    private fun backends(test: ((Path)->JdbcStorageBackend,Path,String)->Unit) {
        val folder=Files.createTempDirectory("guardian-item-protection")
        test(::SqliteStorageBackend,folder.resolve("audit.sqlite"),"jdbc:sqlite:")
        test(::DuckDbStorageBackend,folder.resolve("audit.duckdb"),"jdbc:duckdb:")
    }
    private fun prepare(db: JdbcStorageBackend,row: ContainerTransactionSnapshot=row(),time: Long=200): ItemRollbackRecord {
        db.append(listOf(ContainerAuditEntry(row)))
        return db.prepareItemRollback(UUID.randomUUID(),time,listOf(row))
    }
    private fun phase(record: ItemRollbackRecord,next: ItemRollbackPhase)=ItemRollbackRecord(record.operationId,record.createdAt,next,record.entries)
    private fun owners(record: ItemRollbackRecord)=record.entries.flatMap { it.changes.map { change->change.address.owner } }.toSet()
    @Test fun completedProtectedRecordSurvivesRestartWithClaimsAndRecoveryVisibility() = backends { factory,path,url ->
        val row=row();lateinit var record: ItemRollbackRecord
        factory(path).use { db ->
            db.open();record=prepare(db,row);assertTrue(db.protectItemRollback(record))
            assertTrue(db.transitionItemRollback(record.operationId,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING))
            assertTrue(db.transitionItemRollback(record.operationId,ItemRollbackPhase.APPLYING,ItemRollbackPhase.COMPLETED))
            assertEquals(1L,db.health().unfinishedItemRollbacks)
            assertEquals(ItemRollbackPhase.COMPLETED,db.unfinishedItemRollbacks().single().phase)
            assertEquals(owners(record),db.guardContainerHistory(listOf(row)).reservedOwners)
        }
        factory(path).use { db ->
            db.open();assertTrue(db.itemRollbackProtected(record.operationId))
            assertEquals(ItemRollbackPhase.COMPLETED,db.itemRollback(record.operationId)?.phase)
            assertEquals(1,db.unfinishedItemRollbacks().size)
            assertEquals(row.changes,db.lookupContainers(ContainerLookupQuery()).single().changes)
            assertFails { db.prepareItemRollback(UUID.randomUUID(),201,listOf(row)) }
        }
        DriverManager.getConnection(url+path).use { c ->
            fun count(table: String)=c.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM $table").use { r->r.next();r.getInt(1) } }
            assertEquals(1,count("ex_item_rollback_protection"));assertEquals(2,count("ex_item_rollback_owner"))
            assertEquals(1,count("ex_item_rollback_claim"));assertEquals(1,count("ex_container"))
        }
    }
    @Test fun protectedPreparedCannotBeCancelledOrHaveClaimsReleased() = backends { factory,path,_ ->
        factory(path).use { db ->
            db.open();val row=row();val record=prepare(db,row);assertTrue(db.protectItemRollback(record))
            assertFalse(db.transitionItemRollback(record.operationId,ItemRollbackPhase.PREPARED,ItemRollbackPhase.CANCELLED))
            assertEquals(ItemRollbackPhase.PREPARED,db.itemRollback(record.operationId)?.phase)
            assertEquals(owners(record),db.guardContainerHistory(listOf(row)).reservedOwners)
            assertTrue(db.itemRollbackProtected(record.operationId))
        }
    }
    @Test fun repeatedRegistrationIsIdempotentAndListingDoesNotDuplicateHeaders() = backends { factory,path,_ ->
        factory(path).use { db ->
            db.open();val record=prepare(db);repeat(3){assertTrue(db.protectItemRollback(record))}
            assertEquals(listOf(record.operationId),db.unfinishedItemRollbacks().map { it.operationId })
            assertEquals(1L,db.health().unfinishedItemRollbacks)
        }
    }
    @Test fun changedTimestampOrSourceIdRefusesWithoutRegisteringProtection() = backends { factory,path,_ ->
        factory(path).use { db ->
            db.open();val record=prepare(db)
            assertFalse(db.protectItemRollback(ItemRollbackRecord(record.operationId,201,record.phase,record.entries)))
            assertFalse(db.protectItemRollback(ItemRollbackRecord(record.operationId,record.createdAt,record.phase,listOf(
                ItemRollbackEntry(UUID.randomUUID(),record.entries.single().changes)))))
            assertFalse(db.itemRollbackProtected(record.operationId));assertTrue(db.protectItemRollback(record))
        }
    }
    @Test fun conservingButChangedComponentsRefuseExactRecordMatch() = backends { factory,path,_ ->
        factory(path).use { db ->
            db.open();val record=prepare(db);val changed=item.copy(itemData=BinaryPayload.of(byteArrayOf(3)))
            val edits=record.entries.single().changes.map { it.copy(before=if(it.before.isEmpty)it.before else changed,after=if(it.after.isEmpty)it.after else changed) }
            assertFalse(db.protectItemRollback(ItemRollbackRecord(record.operationId,record.createdAt,record.phase,listOf(ItemRollbackEntry(record.entries.single().transactionId,edits)))))
            assertFalse(db.itemRollbackProtected(record.operationId))
        }
    }
    @Test fun protectionCannotBeRegisteredAfterIntentOrCompletion() = backends { factory,path,_ ->
        factory(path).use { db ->
            db.open();val record=prepare(db)
            db.transitionItemRollback(record.operationId,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING)
            assertFalse(db.protectItemRollback(record))
            assertFailsWith<IllegalArgumentException>{db.protectItemRollback(phase(record,ItemRollbackPhase.APPLYING))}
            db.transitionItemRollback(record.operationId,ItemRollbackPhase.APPLYING,ItemRollbackPhase.COMPLETED)
            assertFalse(db.protectItemRollback(record));assertFalse(db.itemRollbackProtected(record.operationId))
            assertTrue(db.unfinishedItemRollbacks().isEmpty())
        }
    }
    @Test fun absentOperationRefusesWithoutCreatingOrphanMarker() = backends { factory,path,_ ->
        factory(path).use { db ->
            db.open();val record=prepare(db);val missing=ItemRollbackRecord(UUID.randomUUID(),record.createdAt,record.phase,record.entries)
            assertFalse(db.protectItemRollback(missing));assertFalse(db.itemRollbackProtected(missing.operationId))
        }
    }
    @Test fun missingOwnerClaimRefusesRatherThanRepairingIt() = backends { factory,path,url ->
        factory(path).use { db ->
            db.open();val record=prepare(db)
            DriverManager.getConnection(url+path).use { c ->c.createStatement().use { it.executeUpdate("DELETE FROM ex_item_rollback_owner") } }
            assertFalse(db.protectItemRollback(record));assertFalse(db.itemRollbackProtected(record.operationId))
        }
    }
    @Test fun foreignSourceClaimRefusesWithoutTouchingHistory() = backends { factory,path,url ->
        factory(path).use { db ->
            db.open();val record=prepare(db)
            DriverManager.getConnection(url+path).use { c ->c.prepareStatement("UPDATE ex_item_rollback_claim SET operation_uuid = ?").use {it.setString(1,UUID.randomUUID().toString());it.executeUpdate()} }
            assertFalse(db.protectItemRollback(record));assertFalse(db.itemRollbackProtected(record.operationId))
            assertEquals(1,db.lookupContainers(ContainerLookupQuery()).size)
        }
    }
    @Test fun pendingProtectionIsBoundedOrderedAndCountsCompletedExactlyOnce() = backends { factory,path,_ ->
        factory(path).use { db ->
            db.open();val first=prepare(db,row(0),200);val second=prepare(db,row(10),201);val third=prepare(db,row(20),202)
            db.protectItemRollback(first);db.transitionItemRollback(first.operationId,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING)
            db.transitionItemRollback(first.operationId,ItemRollbackPhase.APPLYING,ItemRollbackPhase.COMPLETED)
            assertEquals(listOf(first.operationId,second.operationId),db.unfinishedItemRollbacks(2).map { it.operationId })
            assertEquals(3L,db.health().unfinishedItemRollbacks)
            assertEquals(listOf(first.operationId,second.operationId,third.operationId),db.unfinishedItemRollbacks().map { it.operationId })
            assertFailsWith<IllegalArgumentException>{db.unfinishedItemRollbacks(0)}
            assertFailsWith<IllegalArgumentException>{db.unfinishedItemRollbacks(101)}
        }
    }
    @Test fun interruptedProtectedIntentReopensRecoveryRequiredWithMarkerHeld() = backends { factory,path,_ ->
        lateinit var record: ItemRollbackRecord
        factory(path).use { db ->db.open();record=prepare(db);db.protectItemRollback(record);db.transitionItemRollback(record.operationId,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING)}
        factory(path).use { db ->db.open();assertEquals(ItemRollbackPhase.RECOVERY_REQUIRED,db.itemRollback(record.operationId)?.phase);assertTrue(db.itemRollbackProtected(record.operationId));assertEquals(1L,db.health().unfinishedItemRollbacks)}
    }
    @Test fun schemaEightMigrationPreservesLegacyHistoryAndDoesNotInventReceipts() = backends { factory,path,url ->
        lateinit var record: ItemRollbackRecord
        factory(path).use { db ->db.open();record=prepare(db);db.transitionItemRollback(record.operationId,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING);db.transitionItemRollback(record.operationId,ItemRollbackPhase.APPLYING,ItemRollbackPhase.COMPLETED)}
        DriverManager.getConnection(url+path).use { c -> c.createStatement().use {
            it.executeUpdate("DROP TABLE ex_item_rollback_images")
            it.executeUpdate("DROP TABLE ex_item_rollback_protection")
            it.executeUpdate("DELETE FROM ex_schema_migrations WHERE version >= 9")
            it.executeUpdate("UPDATE ex_meta SET meta_value = '8' WHERE meta_key = 'schema_version'")
        } }
        factory(path).use { db ->
            db.open();assertEquals(GuardianSchema.CURRENT_VERSION,db.health().schemaVersion)
            assertFalse(db.itemRollbackProtected(record.operationId));assertEquals(ItemRollbackPhase.COMPLETED,db.itemRollback(record.operationId)?.phase)
            assertEquals(1,db.lookupContainers(ContainerLookupQuery()).size);assertTrue(db.unfinishedItemRollbacks().isEmpty())
        }
    }
    @Test fun queuedProtectionIsRejectedWithoutRegisteringAfterStop() = backends { factory,path,_ ->
        factory(path).use { db ->
            db.open();val record=prepare(db);val entered=CountDownLatch(1);val release=CountDownLatch(1)
            val journal=object : ItemRollbackProtectionJournal by db {
                override fun itemRollback(operationId: UUID): ItemRollbackRecord? {
                    entered.countDown();check(release.await(5,TimeUnit.SECONDS));return db.itemRollback(operationId)
                }
            }
            val worker=ItemRollbackJournalWorker(journal)
            try {
                val read=worker.read(record.operationId);assertTrue(entered.await(5,TimeUnit.SECONDS));val queued=worker.protect(record)
                worker.close();assertFailsWith<ExecutionException>{queued.toCompletableFuture().get(5,TimeUnit.SECONDS)}
                release.countDown();assertNotNull(read.toCompletableFuture().get(5,TimeUnit.SECONDS));worker.drained.toCompletableFuture().get(5,TimeUnit.SECONDS)
                assertFalse(db.itemRollbackProtected(record.operationId))
            } finally {release.countDown();worker.close();worker.drained.toCompletableFuture().get(5,TimeUnit.SECONDS)}
        }
    }
    @Test fun startedProtectionOutlivesStoppedWorkerAndCancelledObserver() = backends { factory,path,_ ->
        factory(path).use { db ->
            db.open();val record=prepare(db);val entered=CountDownLatch(1);val release=CountDownLatch(1)
            val journal=object : ItemRollbackProtectionJournal by db {
                override fun protectItemRollback(record: ItemRollbackRecord): Boolean {
                    val result=db.protectItemRollback(record);entered.countDown();check(release.await(5,TimeUnit.SECONDS));return result
                }
            }
            val worker=ItemRollbackJournalWorker(journal)
            try {
                val result=worker.protect(record);assertTrue(entered.await(5,TimeUnit.SECONDS))
                worker.close();assertTrue(result.toCompletableFuture().cancel(false));assertFalse(worker.drained.toCompletableFuture().isDone)
                release.countDown();worker.drained.toCompletableFuture().get(5,TimeUnit.SECONDS)
                assertTrue(result.toCompletableFuture().get(5,TimeUnit.SECONDS));assertTrue(db.itemRollbackProtected(record.operationId))
            } finally {release.countDown();worker.close();worker.drained.toCompletableFuture().get(5,TimeUnit.SECONDS)}
        }
    }
}
