package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.*
import com.bareminimumstudios.guardian.storage.query.ContainerLookupQuery
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

class ItemRollbackImagesStorageTest {
    private val a=ItemSlotAddress(ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"),BlockPosition(0,64,0)),0)
    private val b=ItemSlotAddress(ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"),BlockPosition(1,64,0)),0)
    private val item=ItemStackSnapshot(ResourceId.parse("minecraft:coal"),2,BinaryPayload.of(byteArrayOf(1,2)))
    private val spare=ItemStackSnapshot(ResourceId.parse("minecraft:diamond_pickaxe"),1,BinaryPayload.of(byteArrayOf(11)))
    private fun row(time: Long=100)=ContainerTransactionSnapshot(UUID.randomUUID(),time,ActorIdentity.System("hopper"),0,ContainerAction.HOPPER_TRANSFER,listOf(ItemSlotChange(a,item,ItemStackSnapshot.EMPTY),ItemSlotChange(b,ItemStackSnapshot.EMPTY,item)))
    private fun original()=InventorySnapshot(mapOf(a to ItemStackSnapshot.EMPTY,a.copy(index=1) to spare,a.copy(index=2) to ItemStackSnapshot.EMPTY,b to item,b.copy(index=1) to ItemStackSnapshot.EMPTY,b.copy(index=2) to spare))
    private fun prepare(db: JdbcStorageBackend): ItemRollbackRecord {
        val row=row();db.append(listOf(ContainerAuditEntry(row)));return db.prepareItemRollback(UUID.randomUUID(),200,listOf(row))
    }
    private fun protect(db: JdbcStorageBackend): ItemRollbackImages {
        val record=prepare(db);val images=ItemRollbackImages(record,original());assertTrue(db.protectItemRollback(record,images.original));return images
    }
    private fun move(db: JdbcStorageBackend, images: ItemRollbackImages, phase: ItemRollbackPhase=ItemRollbackPhase.COMPLETED): ItemRollbackRecord {
        db.transitionItemRollback(images.record.operationId,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING)
        db.transitionItemRollback(images.record.operationId,ItemRollbackPhase.APPLYING,phase)
        return assertNotNull(db.itemRollback(images.record.operationId))
    }
    private fun backends(test: ((Path)->JdbcStorageBackend,Path,String)->Unit) {
        val folder=Files.createTempDirectory("guardian-owner-images")
        test(::SqliteStorageBackend,folder.resolve("audit.sqlite"),"jdbc:sqlite:")
        test(::DuckDbStorageBackend,folder.resolve("audit.duckdb"),"jdbc:duckdb:")
    }
    private fun held(db: JdbcStorageBackend,id: UUID) {assertTrue(db.itemRollbackProtected(id));assertEquals(1L,db.health().unfinishedItemRollbacks)}
    @Test fun completeImagesSurviveRestartIncludingUnchangedAndEmptySlots() = backends { factory,path,_ ->
        lateinit var images: ItemRollbackImages
        factory(path).use {db->db.open();images=protect(db);move(db,images)}
        factory(path).use {db->db.open();val saved=assertNotNull(db.itemRollbackImages(images.record.operationId));assertFalse(saved.acknowledged);assertEquals(images.original.slots,saved.images.original.slots);assertEquals(images.expected.slots,saved.images.expected.slots);held(db,images.record.operationId)}
    }
    @Test fun repeatedRegistrationCannotOverwriteAnUntouchedSlot() = backends { factory,path,_ ->
        factory(path).use {db->
            db.open();val images=protect(db);assertTrue(db.protectItemRollback(images.record,images.original))
            val changed=images.original.slots.toMutableMap();changed[a.copy(index=1)]=spare.copy(itemData=BinaryPayload.of(byteArrayOf(17)))
            assertFalse(db.protectItemRollback(images.record,InventorySnapshot(changed)))
            assertEquals(images.original.slots,db.itemRollbackImages(images.record.operationId)?.images?.original?.slots)
        }
    }
    @Test fun markerOnlyPreparedRecordCanUpgradeBeforeWrites() = backends { factory,path,_ ->
        factory(path).use {db->db.open();val record=prepare(db);db.protectItemRollback(record);assertNull(db.itemRollbackImages(record.operationId));assertTrue(db.protectItemRollback(record,original()));assertNotNull(db.itemRollbackImages(record.operationId));held(db,record.operationId)}
    }
    @Test fun imagesCannotBeAttachedAfterIntentOrCompletion() = backends { factory,path,_ ->
        factory(path).use {db->
            db.open();val record=prepare(db);db.protectItemRollback(record);db.transitionItemRollback(record.operationId,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING)
            assertFalse(db.protectItemRollback(record,original()));db.transitionItemRollback(record.operationId,ItemRollbackPhase.APPLYING,ItemRollbackPhase.COMPLETED)
            assertFalse(db.protectItemRollback(record,original()));assertNull(db.itemRollbackImages(record.operationId));held(db,record.operationId)
        }
    }
    @Test fun completedAcknowledgmentRetainsSourcesAndDecisionAcrossRestart() = backends { factory,path,url ->
        lateinit var images: ItemRollbackImages;lateinit var record: ItemRollbackRecord
        factory(path).use {db->
            db.open();images=protect(db);record=move(db,images);assertTrue(db.acknowledgeItemRollback(record,images,images.expected))
            assertFalse(db.itemRollbackProtected(record.operationId));assertEquals(0L,db.health().unfinishedItemRollbacks)
            assertTrue(assertNotNull(db.itemRollbackImages(record.operationId)).acknowledged)
        }
        factory(path).use {db->db.open();assertTrue(db.acknowledgeItemRollback(record,images,images.expected));assertTrue(db.unfinishedItemRollbacks().isEmpty());assertEquals(1,db.lookupContainers(ContainerLookupQuery()).size)}
        DriverManager.getConnection(url+path).use {c->
            fun count(table: String)=c.createStatement().use {it.executeQuery("SELECT COUNT(*) FROM $table").use {r->r.next();r.getInt(1)}}
            assertEquals(0,count("ex_item_rollback_owner"));assertEquals(0,count("ex_item_rollback_protection"));assertEquals(1,count("ex_item_rollback_claim"));assertEquals(1,count("ex_item_rollback_images"))
        }
    }
    @Test fun recoveryAcknowledgmentPromotesPhaseAndReleasesClaimsAtomically() = backends { factory,path,_ ->
        factory(path).use {db->
            db.open();val images=protect(db);val recovery=move(db,images,ItemRollbackPhase.RECOVERY_REQUIRED)
            assertTrue(db.acknowledgeItemRollback(recovery,images,images.expected));assertEquals(ItemRollbackPhase.COMPLETED,db.itemRollback(recovery.operationId)?.phase)
            assertTrue(db.acknowledgeItemRollback(recovery,images,images.expected));assertTrue(db.unfinishedItemRollbacks().isEmpty())
        }
    }
    @Test fun originalPartialMissingExtraAndChangedUnloggedSavedSlotsStayHeld() = backends { factory,path,_ ->
        factory(path).use {db->
            db.open();val images=protect(db);val record=move(db,images)
            val partial=images.expected.slots.toMutableMap();partial[b]=item
            val spareChanged=images.expected.slots.toMutableMap();spareChanged[a.copy(index=1)]=item
            val extra=images.expected.slots+(ItemSlotAddress(ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"),BlockPosition(99,64,0)),0) to item)
            for(bad in listOf(images.original,InventorySnapshot(partial),InventorySnapshot(images.expected.slots-a.copy(index=2)),InventorySnapshot(extra),InventorySnapshot(spareChanged))) {
                assertFalse(db.acknowledgeItemRollback(record,images,bad));held(db,record.operationId)
            }
            assertFalse(assertNotNull(db.itemRollbackImages(record.operationId)).acknowledged)
        }
    }
    @Test fun staleHeaderOrForeignReceiptCannotAcknowledge() = backends { factory,path,_ ->
        factory(path).use {db->
            db.open();val images=protect(db);val record=move(db,images)
            val stale=ItemRollbackRecord(record.operationId,201,record.phase,record.entries)
            assertFalse(db.acknowledgeItemRollback(stale,images,images.expected))
            val other=ItemRollbackRecord(UUID.randomUUID(),images.record.createdAt,images.record.phase,images.record.entries)
            assertFalse(db.acknowledgeItemRollback(record,ItemRollbackImages(other,images.original),images.expected));held(db,record.operationId)
        }
    }
    @Test fun preparedRecordCannotBeAcknowledgedFromAnyImage() = backends { factory,path,_ ->
        factory(path).use {db->db.open();val images=protect(db);assertFailsWith<IllegalArgumentException>{db.acknowledgeItemRollback(images.record,images,images.expected)};held(db,images.record.operationId)}
    }
    @Test fun legacyMarkerWithoutImagesCannotBeReleased() = backends { factory,path,_ ->
        factory(path).use {db->
            db.open();val record=prepare(db);val images=ItemRollbackImages(record,original());db.protectItemRollback(record);val completed=move(db,images)
            assertFalse(db.acknowledgeItemRollback(completed,images,images.expected));held(db,record.operationId)
        }
    }
    @Test fun corruptedPayloadCannotBeReadOrAcknowledged() = backends { factory,path,url ->
        factory(path).use {db->
            db.open();val images=protect(db);val record=move(db,images)
            DriverManager.getConnection(url+path).use {c->c.prepareStatement("UPDATE ex_item_rollback_images SET payload = ?").use {it.setBytes(1,byteArrayOf(1,2));it.executeUpdate()}}
            assertFails{db.itemRollbackImages(record.operationId)};assertFails{db.acknowledgeItemRollback(record,images,images.expected)};held(db,record.operationId)
        }
    }
    @Test fun inconsistentAcknowledgmentFlagCannotMasqueradeAsAReleasedDecision() = backends { factory,path,url ->
        factory(path).use {db->
            db.open();val images=protect(db);val record=move(db,images)
            DriverManager.getConnection(url+path).use {c->c.createStatement().use {it.executeUpdate("UPDATE ex_item_rollback_images SET acknowledged = 1")}}
            assertFails{db.itemRollbackImages(record.operationId)};assertFails{db.acknowledgeItemRollback(record,images,images.expected)};held(db,record.operationId)
        }
    }
    @Test fun missingSourceClaimsRefuseWithoutRegisteringImages() = backends { factory,path,url ->
        factory(path).use {db->
            db.open();val record=prepare(db)
            DriverManager.getConnection(url+path).use {c->c.createStatement().use {it.executeUpdate("DELETE FROM ex_item_rollback_claim")}}
            assertFalse(db.protectItemRollback(record,original()));assertNull(db.itemRollbackImages(record.operationId));assertFalse(db.itemRollbackProtected(record.operationId))
        }
    }
    @Test fun repeatedOldDecisionNeverDeletesANewerOperationsOwnerClaims() = backends { factory,path,_ ->
        factory(path).use {db->
            db.open();val images=protect(db);val completed=move(db,images);assertTrue(db.acknowledgeItemRollback(completed,images,images.expected))
            val newer=row(101);db.append(listOf(ContainerAuditEntry(newer)));val next=db.prepareItemRollback(UUID.randomUUID(),201,listOf(newer))
            assertTrue(db.acknowledgeItemRollback(completed,images,images.expected));assertEquals(listOf(next.operationId),db.unfinishedItemRollbacks().map {it.operationId})
            assertEquals(setOf(a.owner,b.owner),db.guardContainerHistory(listOf(newer)).reservedOwners)
        }
    }
    @Test fun schemaNineMarkerUpgradesWithoutFabricatedImagesOrRelease() = backends { factory,path,url ->
        lateinit var images: ItemRollbackImages
        factory(path).use {db->db.open();val record=prepare(db);images=ItemRollbackImages(record,original());db.protectItemRollback(record);move(db,images)}
        DriverManager.getConnection(url+path).use {c->c.createStatement().use {
            it.executeUpdate("DROP TABLE ex_item_rollback_images");it.executeUpdate("DELETE FROM ex_schema_migrations WHERE version = 10");it.executeUpdate("UPDATE ex_meta SET meta_value = '9' WHERE meta_key = 'schema_version'")
        }}
        factory(path).use {db->db.open();assertEquals(10,db.health().schemaVersion);assertNull(db.itemRollbackImages(images.record.operationId));assertFalse(db.acknowledgeItemRollback(assertNotNull(db.itemRollback(images.record.operationId)),images,images.expected));held(db,images.record.operationId)}
    }
    @Test fun interruptedApplyingImagesReopenForExplicitReadOnlyReconciliation() = backends { factory,path,_ ->
        lateinit var images: ItemRollbackImages
        factory(path).use {db->db.open();images=protect(db);db.transitionItemRollback(images.record.operationId,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING)}
        factory(path).use {db->db.open();val record=assertNotNull(db.itemRollback(images.record.operationId));assertEquals(ItemRollbackPhase.RECOVERY_REQUIRED,record.phase);assertEquals(images.expected.slots,db.itemRollbackImages(record.operationId)?.images?.expected?.slots);assertFalse(db.acknowledgeItemRollback(record,images,images.original));held(db,record.operationId)}
    }
    @Test fun fullPlayerArmorAndOffhandComponentsSurviveDurableAcknowledgmentAndRestart() = backends {factory,path,_ ->
        val uuid=UUID.randomUUID();val player=ItemSlotOwner.PlayerInventory(uuid);val slot=ItemSlotAddress(player,0)
        lateinit var record: ItemRollbackRecord;lateinit var images: ItemRollbackImages
        factory(path).use {db->
            db.open();val row=ContainerTransactionSnapshot(UUID.randomUUID(),100,ActorIdentity.Player(uuid,"GuardianReceiptTest"),0,ContainerAction.QUICK_MOVE,
                listOf(ItemSlotChange(a,item,ItemStackSnapshot.EMPTY),ItemSlotChange(slot,ItemStackSnapshot.EMPTY,item)))
            db.append(listOf(ContainerAuditEntry(row)));record=db.prepareItemRollback(UUID.randomUUID(),200,listOf(row))
            val map=linkedMapOf(a to ItemStackSnapshot.EMPTY,a.copy(index=1) to spare,a.copy(index=2) to ItemStackSnapshot.EMPTY)
            repeat(41){map[ItemSlotAddress(player,it)]=ItemStackSnapshot.EMPTY};map[slot]=item;map[ItemSlotAddress(player,36)]=spare;map[ItemSlotAddress(player,40)]=spare
            images=ItemRollbackImages(record,InventorySnapshot(map));assertTrue(db.protectItemRollback(record,images.original))
            val completed=move(db,images);assertTrue(db.acknowledgeItemRollback(completed,images,images.expected))
        }
        factory(path).use {db->
            db.open();val receipt=assertNotNull(db.itemRollbackImages(record.operationId));assertTrue(receipt.acknowledged)
            assertEquals(44,receipt.images.expected.slots.size);assertEquals(images.expected.slots,receipt.images.expected.slots)
            assertEquals(spare,receipt.images.expected.slots[ItemSlotAddress(player,36)]);assertEquals(spare,receipt.images.expected.slots[ItemSlotAddress(player,40)])
        }
    }
    @Test fun sqliteFailureRollsBackPhaseDecisionAndOwnerReleaseTogether() {
        val path=Files.createTempDirectory("guardian-reconcile-atomic").resolve("audit.sqlite")
        SqliteStorageBackend(path).use {db->
            db.open();val images=protect(db);val recovery=move(db,images,ItemRollbackPhase.RECOVERY_REQUIRED)
            DriverManager.getConnection("jdbc:sqlite:$path").use {c->c.createStatement().use {it.executeUpdate("CREATE TRIGGER guardian_test_abort BEFORE DELETE ON ex_item_rollback_protection BEGIN SELECT RAISE(ABORT, 'test'); END")}}
            assertFails{db.acknowledgeItemRollback(recovery,images,images.expected)}
            assertEquals(ItemRollbackPhase.RECOVERY_REQUIRED,db.itemRollback(recovery.operationId)?.phase);assertFalse(assertNotNull(db.itemRollbackImages(recovery.operationId)).acknowledged);held(db,recovery.operationId)
            DriverManager.getConnection("jdbc:sqlite:$path").use {c->c.createStatement().use {it.executeUpdate("DROP TRIGGER guardian_test_abort")}}
            assertTrue(db.acknowledgeItemRollback(recovery,images,images.expected))
        }
    }
}
