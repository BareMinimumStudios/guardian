package com.bareminimumstudios.guardian.storage.jdbc

import java.sql.DriverManager
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.*
import com.bareminimumstudios.guardian.storage.codec.ItemRollbackImagesCodec
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class ItemRestoreJournalTest {
    private val dimension = ResourceId.parse("minecraft:overworld")
    private val a = ItemSlotAddress(ItemSlotOwner.BlockContainer(dimension,BlockPosition(1,64,1)),0)
    private val b = ItemSlotAddress(ItemSlotOwner.BlockContainer(dimension,BlockPosition(2,64,1)),0)
    private val item = ItemStackSnapshot(ResourceId.parse("minecraft:coal"),9,BinaryPayload.of(byteArrayOf(1,2,3)))
    private fun row(time:Long=100) = ContainerTransactionSnapshot(UUID.randomUUID(),time,ActorIdentity.System("hopper"),0,
        ContainerAction.HOPPER_TRANSFER,listOf(ItemSlotChange(a,item,ItemStackSnapshot.EMPTY),ItemSlotChange(b,ItemStackSnapshot.EMPTY,item)))
    private fun original() = InventorySnapshot(mapOf(a to ItemStackSnapshot.EMPTY,b to item,ItemSlotAddress(a.owner,1) to ItemStackSnapshot.EMPTY,ItemSlotAddress(b.owner,1) to ItemStackSnapshot.EMPTY))
    private fun phase(record:ItemRollbackRecord,value:ItemRollbackPhase) = ItemRollbackRecord(record.operationId,record.createdAt,value,record.entries,record.purpose,record.redoOf)
    private fun backends(test:((Path)->JdbcStorageBackend,Path)->Unit) {
        val folder=Files.createTempDirectory("guardian-item-redo")
        test(::SqliteStorageBackend,folder.resolve("test.sqlite"))
        test(::DuckDbStorageBackend,folder.resolve("test.duckdb"))
    }
    private fun completed(db:JdbcStorageBackend):Pair<ItemRollbackRecord,ItemRollbackImages> {
        val row=row();db.append(listOf(ContainerAuditEntry(row)))
        val record=db.prepareItemRollback(UUID.randomUUID(),200,listOf(row));val images=ItemRollbackImages(record,original())
        assertTrue(db.protectItemRollback(record,original()))
        assertTrue(db.transitionItemRollback(record.operationId,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING))
        assertTrue(db.transitionItemRollback(record.operationId,ItemRollbackPhase.APPLYING,ItemRollbackPhase.COMPLETED))
        assertTrue(db.acknowledgeItemRollback(phase(record,ItemRollbackPhase.COMPLETED),images,images.expected))
        return phase(record,ItemRollbackPhase.COMPLETED) to images
    }

    @Test fun savedTransferRollbackHasAnExactConservingInverseAndSingleRedoClaim() = backends { factory,path ->
        factory(path).use { db ->
            db.open();val (parent,images)=completed(db);val source=db.itemRestoreSource(parent.operationId)
            assertTrue(source.guard.clear);assertEquals(1,source.preview(images.expected).eligible)
            val redo=db.prepareItemRestore(UUID.randomUUID(),300,parent.operationId)
            assertEquals(ItemRecoveryPurpose.TRANSFER_REDO,redo.purpose);assertEquals(parent.operationId,redo.redoOf)
            assertEquals(images.original.slots,ItemRollbackImages(redo,images.expected).expected.slots)
            assertEquals(redo.operationId,db.prepareItemRestore(redo.operationId,300,parent.operationId).operationId)
            assertFails { db.prepareItemRestore(UUID.randomUUID(),301,parent.operationId) }
            assertFails { db.prepareItemRollback(UUID.randomUUID(),301,listOf(row())) }
        }
    }

    @Test fun completedRedoRetainsParentAndChildReceiptsAndPreventsReplay() = backends { factory,path ->
        val ids=factory(path).use { db ->
            db.open();val (parent,images)=completed(db);val redo=db.prepareItemRestore(UUID.randomUUID(),300,parent.operationId)
            val redoImages=ItemRollbackImages(redo,images.expected)
            assertTrue(db.protectItemRollback(redo,images.expected))
            assertTrue(db.transitionItemRollback(redo.operationId,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING))
            assertTrue(db.transitionItemRollback(redo.operationId,ItemRollbackPhase.APPLYING,ItemRollbackPhase.COMPLETED))
            assertTrue(db.acknowledgeItemRollback(phase(redo,ItemRollbackPhase.COMPLETED),redoImages,images.original))
            assertTrue(db.itemRollbackImages(parent.operationId)!!.acknowledged)
            assertTrue(db.itemRollbackImages(redo.operationId)!!.acknowledged)
            parent.operationId to redo.operationId
        }
        factory(path).use { db ->
            db.open();assertEquals(ItemRollbackPhase.COMPLETED,db.itemRollback(ids.second)?.phase)
            assertEquals(ids.first,db.itemRollback(ids.second)?.redoOf)
            assertFails { db.prepareItemRestore(UUID.randomUUID(),400,ids.first) }
        }
    }

    @Test fun schema28MigrationPreservesProtectedRedoReceiptsAndClaims() = backends { factory,path ->
        val ids=factory(path).use { db ->
            db.open();val (parent,images)=completed(db)
            val redo=db.prepareItemRestore(UUID.randomUUID(),300,parent.operationId)
            assertTrue(db.protectItemRollback(redo,images.expected))
            assertTrue(db.transitionItemRollback(redo.operationId,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING))
            parent.operationId to redo.operationId
        }
        DriverManager.getConnection((if(path.toString().endsWith("duckdb"))"jdbc:duckdb:" else "jdbc:sqlite:")+path).use { connection ->
            connection.createStatement().use {
                it.executeUpdate("DROP TABLE ex_checkpoint_restore_claim")
                it.executeUpdate("DROP TABLE ex_checkpoint_restore_link")
                it.executeUpdate("DROP INDEX ex_checkpoint_player_idx")
                it.executeUpdate("DROP TABLE ex_inventory_checkpoint")
                it.executeUpdate("DELETE FROM ex_schema_migrations WHERE version>=29")
                it.executeUpdate("UPDATE ex_meta SET meta_value='28' WHERE meta_key='schema_version'")
            }
        }
        factory(path).use { db ->
            db.open();val parent=assertNotNull(db.itemRollbackImages(ids.first));val redo=assertNotNull(db.itemRollbackImages(ids.second))
            assertTrue(parent.acknowledged);assertFalse(redo.acknowledged)
            assertEquals(ItemRecoveryPurpose.TRANSFER_REDO,redo.images.record.purpose)
            assertEquals(ids.first,redo.images.record.redoOf)
            assertEquals(ItemRollbackPhase.RECOVERY_REQUIRED,redo.images.record.phase)
            assertEquals(parent.images.expected.slots,redo.images.original.slots)
            assertEquals(parent.images.original.slots,redo.images.expected.slots)
            assertTrue(db.itemRollbackProtected(ids.second))
            assertFails { db.prepareItemRestore(UUID.randomUUID(),400,ids.first) }
            assertTrue(db.inventoryCheckpoints().isEmpty())
        }
    }

    @Test fun cancelledUnstartedRedoReleasesOnlyItsOwnClaim() = backends { factory,path ->
        factory(path).use { db ->
            db.open();val (parent,images)=completed(db);val redo=db.prepareItemRestore(UUID.randomUUID(),300,parent.operationId)
            assertTrue(db.protectItemRollback(redo,images.expected))
            assertTrue(db.cancelUnstartedItemRollback(redo))
            assertEquals(parent.operationId,db.itemRollback(redo.operationId)?.redoOf)
            assertTrue(db.itemRollbackImages(parent.operationId)!!.acknowledged)
            db.prepareItemRestore(UUID.randomUUID(),301,parent.operationId)
        }
    }

    @Test fun newerHistoryOrChangedCompleteImagesRefuseRedo() = backends { factory,path ->
        factory(path).use { db ->
            db.open();val (parent,images)=completed(db);val source=db.itemRestoreSource(parent.operationId)
            assertEquals(0,source.preview(images.original).eligible)
            val unrelated=InventorySnapshot(images.expected.slots + (ItemSlotAddress(a.owner,1) to item))
            assertEquals(0,source.preview(unrelated).eligible)
            db.append(listOf(ContainerAuditEntry(row(201))))
            assertTrue(db.itemRestoreSource(parent.operationId).guard.newerOwners.isNotEmpty())
            assertFails { db.prepareItemRestore(UUID.randomUUID(),300,parent.operationId) }
        }
    }

    @Test fun interruptedRedoRetainsPurposeParentAndBoundImages() = backends { factory,path ->
        val ids=factory(path).use { db ->
            db.open();val (parent,images)=completed(db);val redo=db.prepareItemRestore(UUID.randomUUID(),300,parent.operationId)
            assertTrue(db.protectItemRollback(redo,images.expected))
            val payload=ItemRollbackImagesCodec.encode(ItemRollbackImages(redo,images.expected))
            val counterfeit=ItemRollbackRecord(redo.operationId,redo.createdAt,redo.phase,redo.entries,redo.purpose,UUID.randomUUID())
            assertFails { ItemRollbackImagesCodec.decode(counterfeit,payload) }
            assertTrue(db.transitionItemRollback(redo.operationId,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING))
            parent.operationId to redo.operationId
        }
        factory(path).use { db ->
            db.open();val record=assertNotNull(db.itemRollback(ids.second))
            assertEquals(ItemRollbackPhase.RECOVERY_REQUIRED,record.phase);assertEquals(ids.first,record.redoOf)
            assertEquals(ItemRecoveryPurpose.TRANSFER_REDO,record.purpose)
            assertFalse(db.itemRollbackImages(ids.second)!!.acknowledged)
            assertFails { db.prepareItemRestore(UUID.randomUUID(),400,ids.first) }
        }
    }

    @Test fun unprotectedCancellationPreservesTheRedoOpportunity() = backends { factory,path ->
        factory(path).use { db ->
            db.open();val (parent,_)=completed(db);val redo=db.prepareItemRestore(UUID.randomUUID(),300,parent.operationId)
            assertTrue(db.transitionItemRollback(redo.operationId,ItemRollbackPhase.PREPARED,ItemRollbackPhase.CANCELLED))
            assertEquals(parent.operationId,db.itemRollback(redo.operationId)?.redoOf)
            assertTrue(db.restorableItemRollbacks().any { it.operationId==parent.operationId })
            db.prepareItemRestore(UUID.randomUUID(),301,parent.operationId)
        }
    }

    @Test fun multiTransactionRedoUsesTheOriginalForwardOrder() = backends { factory,path ->
        factory(path).use { db ->
            db.open()
            val c=ItemSlotAddress(ItemSlotOwner.BlockContainer(dimension,BlockPosition(3,64,1)),0)
            val first=row(100)
            val second=ContainerTransactionSnapshot(UUID.randomUUID(),101,ActorIdentity.System("hopper"),0,ContainerAction.HOPPER_TRANSFER,
                listOf(ItemSlotChange(b,item,ItemStackSnapshot.EMPTY),ItemSlotChange(c,ItemStackSnapshot.EMPTY,item)))
            db.append(listOf(ContainerAuditEntry(first),ContainerAuditEntry(second)))
            val record=db.prepareItemRollback(UUID.randomUUID(),200,listOf(second,first))
            val original=InventorySnapshot(mapOf(a to ItemStackSnapshot.EMPTY,b to ItemStackSnapshot.EMPTY,c to item))
            val images=ItemRollbackImages(record,original)
            assertTrue(db.protectItemRollback(record,original))
            assertTrue(db.transitionItemRollback(record.operationId,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING))
            assertTrue(db.transitionItemRollback(record.operationId,ItemRollbackPhase.APPLYING,ItemRollbackPhase.COMPLETED))
            assertTrue(db.acknowledgeItemRollback(phase(record,ItemRollbackPhase.COMPLETED),images,images.expected))
            val redo=db.prepareItemRestore(UUID.randomUUID(),300,record.operationId)
            assertEquals(listOf(first.transactionId,second.transactionId),redo.entries.map { it.transactionId })
            assertEquals(original.slots,ItemRollbackImages(redo,images.expected).expected.slots)
        }
    }

    @Test fun redoReceiptCannotReplaceUnchangedSavedSlots() = backends { factory,path ->
        factory(path).use { db ->
            db.open();val (parent,images)=completed(db)
            val redo=db.prepareItemRestore(UUID.randomUUID(),300,parent.operationId)
            val unrelated=ItemSlotAddress(a.owner,1)
            val forgedOriginal=InventorySnapshot(images.expected.slots + (unrelated to item))
            assertFalse(db.protectItemRollback(redo,forgedOriginal))
            assertFalse(db.itemRollbackProtected(redo.operationId))
            assertTrue(db.protectItemRollback(redo,images.expected))
            assertEquals(images.expected.slots,db.itemRollbackImages(redo.operationId)!!.images.original.slots)
        }
    }

    @Test fun arbitraryOrUnacknowledgedOperationsCannotAuthorizeRedo() = backends { factory,path ->
        factory(path).use { db ->
            db.open();assertFails { db.itemRestoreSource(UUID.randomUUID()) }
            val row=row();db.append(listOf(ContainerAuditEntry(row)))
            val record=db.prepareItemRollback(UUID.randomUUID(),200,listOf(row))
            assertFails { db.prepareItemRestore(UUID.randomUUID(),300,record.operationId) }
            assertFails { ItemRollbackRecord(UUID.randomUUID(),200,ItemRollbackPhase.PREPARED,record.entries,ItemRecoveryPurpose.TRANSFER_REDO) }
        }
    }
}
