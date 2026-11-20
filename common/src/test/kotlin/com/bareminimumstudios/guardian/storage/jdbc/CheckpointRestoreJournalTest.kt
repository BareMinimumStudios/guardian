package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.checkpoint.*
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.*
import com.bareminimumstudios.guardian.storage.codec.ItemRollbackImagesCodec
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class CheckpointRestoreJournalTest {
    private val player=UUID.randomUUID()
    private val owner=ItemSlotOwner.PlayerInventory(player)
    private val coal=ItemStackSnapshot(ResourceId.parse("minecraft:coal"),9,BinaryPayload.of(byteArrayOf(1,2,3)))
    private val stone=ItemStackSnapshot(ResourceId.parse("minecraft:stone"),1,BinaryPayload.of(byteArrayOf(4,5,6)))
    private val requester=CheckpointRequester(UUID.randomUUID(),"Admin")
    private fun checkpoint(item:ItemStackSnapshot,time:Long=100,reason:InventoryCheckpointReason=InventoryCheckpointReason.MANUAL,version:String="1.21.1") =
        InventoryCheckpoint(UUID.randomUUID(),player,"Alex",time,version,reason,ResourceId.parse("minecraft:overworld"),BlockPosition(1,64,1),
            InventorySnapshot((0..40).associate { ItemSlotAddress(owner,it) to if(it==0)item else ItemStackSnapshot.EMPTY }))
    private fun before(item:ItemStackSnapshot=stone,time:Long=200)=checkpoint(item,time,InventoryCheckpointReason.BEFORE_RESTORE)
    private fun phase(record:ItemRollbackRecord,value:ItemRollbackPhase)=ItemRollbackRecord(record.operationId,record.createdAt,value,record.entries,record.purpose,record.redoOf,record.checkpointOf)
    private fun backends(test:((Path)->JdbcStorageBackend,Path)->Unit) {
        val folder=Files.createTempDirectory("guardian-checkpoint-restore")
        test(::SqliteStorageBackend,folder.resolve("test.sqlite"));test(::DuckDbStorageBackend,folder.resolve("test.duckdb"))
    }

    @Test fun replacementHasSeparateCanonicalAuthorityAndSavesThePreviousInventory() = backends { factory,path ->
        factory(path).use { db ->
            db.open();val saved=checkpoint(coal);val original=before();db.saveInventoryCheckpoint(saved,InventoryCheckpointLimits())
            val record=db.prepareCheckpointRestore(UUID.randomUUID(),200,saved.checkpointId,original,requester,InventoryCheckpointLimits())
            assertEquals(ItemRecoveryPurpose.CHECKPOINT_RESTORE,record.purpose);assertEquals(saved.checkpointId,record.checkpointOf)
            assertFalse(ContainerRollbackPlanner.conserving(record.entries.single().changes))
            val images=ItemRollbackImages(record,original.inventory);assertEquals(saved.inventory.slots,images.expected.slots)
            assertEquals(original.inventory.slots,db.inventoryCheckpoint(original.checkpointId)?.inventory?.slots)
            assertEquals(CheckpointRestoreLink(saved.checkpointId,original.checkpointId,requester),db.checkpointRestoreLink(record.operationId))
            assertEquals(record.operationId,db.prepareCheckpointRestore(record.operationId,200,saved.checkpointId,original,requester,InventoryCheckpointLimits()).operationId)
            assertFails { db.prepareCheckpointRestore(UUID.randomUUID(),201,saved.checkpointId,before(time=201),requester,InventoryCheckpointLimits()) }
            val row=ContainerTransactionSnapshot(UUID.randomUUID(),100,ActorIdentity.Player(player,"Alex"),0,ContainerAction.SWAP,record.entries.single().changes)
            assertEquals(0,ContainerRollbackPlanner.plan(listOf(row),original.inventory).eligible)
        }
    }

    @Test fun completedRestoreCanBeUndoneUsingItsSavedBeforeCheckpoint() = backends { factory,path ->
        val ids=factory(path).use { db ->
            db.open();val saved=checkpoint(coal);val original=before();db.saveInventoryCheckpoint(saved,InventoryCheckpointLimits())
            val record=db.prepareCheckpointRestore(UUID.randomUUID(),200,saved.checkpointId,original,requester,InventoryCheckpointLimits())
            val images=ItemRollbackImages(record,original.inventory)
            assertTrue(db.protectItemRollback(record,original.inventory))
            assertTrue(db.transitionItemRollback(record.operationId,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING))
            assertTrue(db.transitionItemRollback(record.operationId,ItemRollbackPhase.APPLYING,ItemRollbackPhase.COMPLETED))
            assertTrue(db.acknowledgeItemRollback(phase(record,ItemRollbackPhase.COMPLETED),images,saved.inventory))
            Triple(saved.checkpointId,original.checkpointId,record.operationId)
        }
        factory(path).use { db ->
            db.open();assertTrue(db.itemRollbackImages(ids.third)!!.acknowledged)
            assertFails { db.checkpointRestoreSource(ids.first) }
            assertFails { db.itemRestoreSource(ids.third) }
            val current=before(coal,time=300)
            val undo=db.prepareCheckpointRestore(UUID.randomUUID(),300,ids.second,current,requester,InventoryCheckpointLimits())
            assertEquals(stone,ItemRollbackImages(undo,current.inventory).expected.slots[ItemSlotAddress(owner,0)])
        }
    }

    @Test fun cancellingAnUnstartedRestoreReleasesOnlyItsCheckpointClaim() = backends { factory,path ->
        factory(path).use { db ->
            db.open();val saved=checkpoint(coal);val original=before();db.saveInventoryCheckpoint(saved,InventoryCheckpointLimits())
            val record=db.prepareCheckpointRestore(UUID.randomUUID(),200,saved.checkpointId,original,requester,InventoryCheckpointLimits())
            assertTrue(db.protectItemRollback(record,original.inventory));assertTrue(db.cancelUnstartedItemRollback(record))
            assertEquals(ItemRollbackPhase.CANCELLED,db.itemRollback(record.operationId)?.phase)
            assertNotNull(db.checkpointRestoreSource(saved.checkpointId))
            db.prepareCheckpointRestore(UUID.randomUUID(),201,saved.checkpointId,before(time=201),requester,InventoryCheckpointLimits())
        }
    }

    @Test fun unchangedSlotsAndCheckpointIdentityAreBoundToTheReceipt() = backends { factory,path ->
        factory(path).use { db ->
            db.open();val saved=checkpoint(coal);val original=before();db.saveInventoryCheckpoint(saved,InventoryCheckpointLimits())
            val record=db.prepareCheckpointRestore(UUID.randomUUID(),200,saved.checkpointId,original,requester,InventoryCheckpointLimits())
            val unrelated=ItemSlotAddress(owner,2)
            assertFalse(db.protectItemRollback(record,InventorySnapshot(original.inventory.slots+(unrelated to coal))))
            assertFalse(db.itemRollbackProtected(record.operationId));assertTrue(db.protectItemRollback(record,original.inventory))
            val bytes=ItemRollbackImagesCodec.encode(ItemRollbackImages(record,original.inventory))
            val other=ItemRollbackRecord(record.operationId,record.createdAt,record.phase,record.entries,record.purpose,checkpointOf=UUID.randomUUID())
            assertFails { ItemRollbackImagesCodec.decode(other,bytes) }
        }
    }

    @Test fun anInterruptedRestoreRetainsBothCompleteCheckpoints() = backends { factory,path ->
        val ids=factory(path).use { db ->
            db.open();val saved=checkpoint(coal);val original=before();db.saveInventoryCheckpoint(saved,InventoryCheckpointLimits())
            val record=db.prepareCheckpointRestore(UUID.randomUUID(),200,saved.checkpointId,original,requester,InventoryCheckpointLimits())
            assertTrue(db.protectItemRollback(record,original.inventory));assertTrue(db.transitionItemRollback(record.operationId,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING))
            Triple(saved.checkpointId,original.checkpointId,record.operationId)
        }
        factory(path).use { db ->
            db.open();val record=assertNotNull(db.itemRollback(ids.third));assertEquals(ItemRollbackPhase.RECOVERY_REQUIRED,record.phase)
            assertEquals(ids.first,record.checkpointOf);assertEquals(ItemRecoveryPurpose.CHECKPOINT_RESTORE,record.purpose)
            assertFalse(db.itemRollbackImages(ids.third)!!.acknowledged)
            assertFailsWith<InventoryCheckpointCapacityException> { db.saveInventoryCheckpoint(checkpoint(coal,time=300),InventoryCheckpointLimits(perPlayer=1,total=2)) }
            assertNotNull(db.inventoryCheckpoint(ids.first));assertNotNull(db.inventoryCheckpoint(ids.second))
        }
    }

    @Test fun budgetRefusalRollsBackTheWholeAdmission() = backends { factory,path ->
        factory(path).use { db ->
            db.open();val saved=checkpoint(coal);val original=before();db.saveInventoryCheckpoint(saved,InventoryCheckpointLimits())
            val id=UUID.randomUUID()
            assertFailsWith<InventoryCheckpointCapacityException> { db.prepareCheckpointRestore(id,200,saved.checkpointId,original,requester,InventoryCheckpointLimits(perPlayer=1,total=1)) }
            assertNull(db.itemRollback(id));assertNull(db.checkpointRestoreLink(id));assertNull(db.inventoryCheckpoint(original.checkpointId))
            assertNotNull(db.checkpointRestoreSource(saved.checkpointId))
        }
    }

    @Test fun incompatibleOrAlreadyMatchingImagesDoNotCreatePlans() {
        val saved=checkpoint(coal);val source=CheckpointRestoreSource(saved)
        val incompatible=checkpoint(stone,200,InventoryCheckpointReason.BEFORE_RESTORE,"1.21.4")
        assertFailsWith<CheckpointRestoreRefusedException> { source.record(UUID.randomUUID(),200,incompatible) }
        assertFailsWith<CheckpointRestoreRefusedException> { source.record(UUID.randomUUID(),200,before(coal)) }
    }
}
