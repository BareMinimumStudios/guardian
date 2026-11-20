package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.codec.ItemRollbackImagesCodec
import com.bareminimumstudios.guardian.storage.jdbc.SqliteStorageBackend
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class CreativeDeletionRecoveryTest {
    private val id=UUID.randomUUID()
    private val owner=ItemSlotOwner.PlayerInventory(id)
    private val item=ItemStackSnapshot(ResourceId("minecraft","coal"),9,BinaryPayload.of(byteArrayOf(1,2,3)))
    private val empty=ItemStackSnapshot.EMPTY
    private fun row(slot:Int=0,time:Long=100,action:ContainerAction=ContainerAction.CREATIVE_SET,after:ItemStackSnapshot=empty)=
        ContainerTransactionSnapshot(UUID.randomUUID(),time,ActorIdentity.Player(id,"CreativeTest"),0,action,
            listOf(ItemSlotChange(ItemSlotAddress(owner,slot),item,after)))
    private fun original()=InventorySnapshot((0..40).associate { ItemSlotAddress(owner,it) to empty })
    @Test fun transferPolicyStillRejectsDeletedItems() {
        val value=row();assertEquals(0,ContainerRollbackPlanner.plan(listOf(value),original()).eligible)
        assertFailsWith<IllegalArgumentException> { ItemRecoveryCheck(ItemRollbackRecord(UUID.randomUUID(),200,ItemRollbackPhase.PREPARED,listOf(ItemRollbackEntry(value.transactionId,value.changes)))) }
    }
    @Test fun explicitDeletionRestoresOnlyRecordedItemsIntoMatchingEmptySlots() {
        val value=row();val plan=ContainerRollbackPlanner.plan(listOf(value),original(),purpose=ItemRecoveryPurpose.CREATIVE_DELETION)
        assertEquals(1,plan.eligible)
        val occupied=InventorySnapshot(original().slots+ (ItemSlotAddress(owner,0) to item))
        assertEquals(ContainerPreviewReason.STATE_MISMATCH,ContainerRollbackPlanner.plan(listOf(value),occupied,purpose=ItemRecoveryPurpose.CREATIVE_DELETION).entries.single().reason)
    }
    @Test fun simultaneousDisjointDeletesCommuteButRepeatedSlotsDoNot() {
        val rows=(0..40).map { row(it) }
        assertEquals(41,ContainerRollbackPlanner.plan(rows,original(),purpose=ItemRecoveryPurpose.CREATIVE_DELETION).eligible)
        assertEquals(0,ContainerRollbackPlanner.plan(listOf(row(),row()),original(),purpose=ItemRecoveryPurpose.CREATIVE_DELETION).eligible)
    }
    @Test fun dropsPartialRemovalsAndMixedPlayersCannotBecomeCreativeRecovery() {
        assertFalse(ItemRecoveryPolicy.transaction(ItemRecoveryPurpose.CREATIVE_DELETION,row(action=ContainerAction.DROP_STACK)))
        assertFalse(ItemRecoveryPolicy.transaction(ItemRecoveryPurpose.CREATIVE_DELETION,row(after=item.copy(count=1))))
        val secondOwner=ItemSlotOwner.PlayerInventory(UUID.randomUUID())
        assertFailsWith<IllegalArgumentException> { ItemRecoveryPolicy.validate(ItemRecoveryPurpose.CREATIVE_DELETION,listOf(
            ItemRollbackEntry(UUID.randomUUID(),row().changes),ItemRollbackEntry(UUID.randomUUID(),listOf(ItemSlotChange(ItemSlotAddress(secondOwner,1),item,empty))))) }
    }
    @Test fun purposePersistsWithProtectedImagesAndCannotBeReinterpretedAsTransfer() {
        val path=Files.createTempDirectory("guardian-creative-purpose").resolve("history.sqlite")
        val value=row();val operation=UUID.randomUUID()
        SqliteStorageBackend(path).use { db ->
            db.open();db.append(listOf(ContainerAuditEntry(value)))
            assertFailsWith<IllegalArgumentException> { db.prepareItemRollback(UUID.randomUUID(),200,listOf(value)) }
            val record=db.prepareCreativeRecovery(operation,200,listOf(value));assertEquals(ItemRecoveryPurpose.CREATIVE_DELETION,record.purpose)
            assertTrue(db.protectItemRollback(record,original()))
            val images=ItemRollbackImages(record,original());val payload=ItemRollbackImagesCodec.encode(images)
            assertEquals(item,ItemRollbackImagesCodec.decode(record,payload).expected.slots[ItemSlotAddress(owner,0)])
            assertFailsWith<IllegalArgumentException> { ItemRollbackImagesCodec.decode(ItemRollbackRecord(record.operationId,record.createdAt,record.phase,record.entries),payload) }
            assertTrue(db.transitionItemRollback(operation,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING))
        }
        SqliteStorageBackend(path).use { db ->db.open()
            val record=assertNotNull(db.itemRollback(operation));assertEquals(ItemRecoveryPurpose.CREATIVE_DELETION,record.purpose);assertEquals(ItemRollbackPhase.RECOVERY_REQUIRED,record.phase)
            assertEquals(item,db.itemRollbackImages(operation)?.images?.expected?.slots?.get(ItemSlotAddress(owner,0)))
            assertFails { db.prepareCreativeRecovery(UUID.randomUUID(),201,listOf(value)) }
        }
    }
}
