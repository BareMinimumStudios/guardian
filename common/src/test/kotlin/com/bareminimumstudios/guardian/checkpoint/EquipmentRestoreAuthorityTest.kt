package com.bareminimumstudios.guardian.checkpoint

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.*
import java.util.UUID
import kotlin.test.*

class EquipmentRestoreAuthorityTest {
    private val player=UUID.randomUUID()
    private val normal=InventorySnapshot((0 until 41).associate { ItemSlotAddress(ItemSlotOwner.PlayerInventory(player),it) to ItemStackSnapshot.EMPTY })
    private val ring=EquipmentSlotAddress("trinkets","hand/ring/0")
    private val item=ItemStackSnapshot(ResourceId.parse("minecraft:diamond"),1,BinaryPayload.of(byteArrayOf(1)))
    private fun equipment(value:ItemStackSnapshot,version:String="3",address:EquipmentSlotAddress=ring)=
        PlayerEquipmentSnapshot(mapOf("trinkets" to version),mapOf(address to value))
    private fun checkpoint(time:Long,extra:PlayerEquipmentSnapshot?,reason:InventoryCheckpointReason)=
        InventoryCheckpoint(UUID.randomUUID(),player,"Alex",time,"1.21.1",reason,ResourceId.parse("minecraft:overworld"),BlockPosition(0,64,0),normal,extra)
    private fun source(extra:PlayerEquipmentSnapshot?)=checkpoint(100,extra,InventoryCheckpointReason.MANUAL)
    private fun before(extra:PlayerEquipmentSnapshot?)=checkpoint(200,extra,InventoryCheckpointReason.BEFORE_RESTORE)

    @Test fun namedEquipmentChangesHaveFullOwnerAuthorityAndUndoImage() {
        val saved=source(equipment(item));val original=before(equipment(ItemStackSnapshot.EMPTY))
        val record=CheckpointRestoreSource(saved).record(UUID.randomUUID(),200,original)
        val images=ItemRollbackImages(record,original.fullInventory)
        assertEquals(saved.fullInventory.slots,images.expected.slots)
        assertEquals(42,images.original.slots.size)
        assertEquals(ItemRecoveryPurpose.CHECKPOINT_RESTORE,record.purpose)
        assertNotNull((record.entries.single().changes.single().address.owner as ItemSlotOwner.PlayerInventory).layout)
        val current=checkpoint(300,saved.equipment,InventoryCheckpointReason.BEFORE_RESTORE)
        val undo=CheckpointRestoreSource(original).record(UUID.randomUUID(),300,current)
        assertEquals(original.fullInventory.slots,ItemRollbackImages(undo,current.fullInventory).expected.slots)
    }
    @Test fun missingProviderVersionAndNamedSlotChangesAreRefused() {
        val saved=source(equipment(item))
        for(extra in listOf(null,equipment(ItemStackSnapshot.EMPTY,"4"),equipment(ItemStackSnapshot.EMPTY,address=EquipmentSlotAddress("trinkets","head/hat/0"))))
            assertFailsWith<CheckpointRestoreRefusedException> { CheckpointRestoreSource(saved).record(UUID.randomUUID(),200,before(extra)) }
        assertFailsWith<CheckpointRestoreRefusedException> {
            CheckpointRestoreSource(source(null)).record(UUID.randomUUID(),200,before(equipment(item)))
        }
    }
    @Test fun aFullCheckpointCanAuthorizeMoreThan64ChangedSlots() {
        val addresses=(0 until 200).map { EquipmentSlotAddress("trinkets","gear/$it") }
        val saved=source(PlayerEquipmentSnapshot(mapOf("trinkets" to "3"),addresses.associateWith { item }))
        val original=before(PlayerEquipmentSnapshot(mapOf("trinkets" to "3"),addresses.associateWith { ItemStackSnapshot.EMPTY }))
        val record=CheckpointRestoreSource(saved).record(UUID.randomUUID(),200,original)
        assertEquals(200,record.entries.single().changes.size)
        assertEquals(saved.fullInventory.slots,ItemRollbackImages(record,original.fullInventory).expected.slots)
    }
}
