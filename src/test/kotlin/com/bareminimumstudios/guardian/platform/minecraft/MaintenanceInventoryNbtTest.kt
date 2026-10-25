package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.*
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.nbt.*
import net.minecraft.world.item.*
import java.util.UUID
import kotlin.test.*

class MaintenanceInventoryNbtTest {
    companion object { init { SharedConstants.tryDetectVersion();Bootstrap.bootStrap() };private val registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY) }
    private val player=ItemSlotOwner.PlayerInventory(UUID.randomUUID())
    private val block=ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"),BlockPosition(0,64,0))
    private val a=ItemSlotAddress(player,0);private val b=ItemSlotAddress(block,0)
    private val item=MinecraftItemSnapshotter.capture(ItemStack(Items.COAL,9).apply { set(DataComponents.CUSTOM_NAME,Component.literal("Tracked components")) },registries)
    private val empty=ItemStackSnapshot.EMPTY
    private val record=ItemRollbackRecord(UUID.randomUUID(),1,ItemRollbackPhase.APPLYING,listOf(ItemRollbackEntry(UUID.randomUUID(),listOf(ItemSlotChange(a,item,empty),ItemSlotChange(b,empty,item)))))
    private val images=ItemRollbackImages(record,InventorySnapshot(buildMap {
        for(i in 0..40)put(ItemSlotAddress(player,i),empty)
        for(i in 0..26)put(ItemSlotAddress(block,i),if(i==0)item else empty)
    }))
    private fun saved(count: Int=9)=MinecraftItemSnapshotter.decodeTag(item.itemData!!.copyBytes()).apply { putInt("count",count);putByte("Slot",0) }
    private fun playerTag(withItem: Boolean=false)=CompoundTag().apply {
        putInt("DataVersion",SharedConstants.getCurrentVersion().dataVersion.version);putUUID("UUID",player.playerId)
        putString("Unrelated","keep player fields")
        put("Inventory",ListTag().apply { if(withItem)add(saved()) })
    }
    private fun chunk()=CompoundTag().apply {
        putInt("DataVersion",SharedConstants.getCurrentVersion().dataVersion.version);putInt("xPos",0);putInt("zPos",0);putString("Status","minecraft:full")
                put("sections",ListTag().apply { add(CompoundTag().apply {
                    putByte("Y",4);put("block_states",CompoundTag().apply {
                        put("palette",ListTag().apply { add(CompoundTag().apply { putString("Name","minecraft:barrel") }) })
                    })
                }) })
        putString("Unrelated","keep chunk fields")
        put("block_entities",ListTag().apply { add(CompoundTag().apply {
            putString("id","minecraft:barrel");putInt("x",0);putInt("y",64);putInt("z",0);putString("CustomName","keep container name")
            put("Items",ListTag().apply { add(saved()) })
        }) })
    }
    @Test fun partialImagesFinishWithoutChangingUnrelatedPlayerOrContainerData() {
        val p=playerTag(true);val c=chunk()
        MaintenanceInventoryNbt.repair(p,player,images);MaintenanceInventoryNbt.repair(c,block,images)
        assertEquals("keep player fields",p.getString("Unrelated"));assertEquals("keep chunk fields",c.getString("Unrelated"))
        val entity=c.getList("block_entities",10).getCompound(0)
        assertEquals("keep container name",entity.getString("CustomName"));assertTrue(entity.getList("Items",10).isEmpty())
        val wanted=images.expected.slots.keys.filter { it.owner==player }.toSet()
        assertEquals(images.expected.slots.filterKeys { it.owner==player },MinecraftSavedPlayerDecoder.decode(p,player,wanted,registries).slots)
        val copy=p.copy();MaintenanceInventoryNbt.repair(p,player,images);assertEquals(copy,p)
    }
    @Test fun thirdStateCountOrUnchangedSlotConflictRefusesWithoutChangingDocument() {
        val c=chunk();c.getList("block_entities",10).getCompound(0).getList("Items",10).getCompound(0).putInt("count",8)
        val original=c.copy();assertFailsWith<IllegalArgumentException> { MaintenanceInventoryNbt.repair(c,block,images) };assertEquals(original,c)
        val p=playerTag();p.getList("Inventory",10).add(saved().apply { putByte("Slot",100) })
        val before=p.copy();assertFailsWith<IllegalArgumentException> { MaintenanceInventoryNbt.repair(p,player,images) };assertEquals(before,p)
    }
    @Test fun duplicateSlotsUnknownFieldsAndPlayerIdentityChangesRefuse() {
        val p=playerTag(true);p.getList("Inventory",10).add(saved())
        assertFailsWith<IllegalArgumentException> { MaintenanceInventoryNbt.repair(p,player,images) }
        val unknown=playerTag(true);unknown.getList("Inventory",10).getCompound(0).putString("mod_extra","must not discard")
        assertFailsWith<IllegalArgumentException> { MaintenanceInventoryNbt.repair(unknown,player,images) }
        val other=playerTag();other.putUUID("UUID",UUID.randomUUID())
        assertFailsWith<IllegalArgumentException> { MaintenanceInventoryNbt.repair(other,player,images) }
    }
    @Test fun paddedMultiEntrySectionPaletteSelectsThePhysicalContainer() {
        val c=chunk()
        val states=c.getList("sections",10).getCompound(0).getCompound("block_states")
        states.put("palette",ListTag().apply { add(CompoundTag().apply { putString("Name","minecraft:air") });add(CompoundTag().apply { putString("Name","minecraft:barrel") }) })
        states.putLongArray("data",LongArray(256).apply { this[0]=1 })
        MaintenanceInventoryNbt.repair(c,block,images)
        assertTrue(c.getList("block_entities",10).getCompound(0).getList("Items",10).isEmpty())
    }
    @Test fun mismatchedPhysicalBlockStateRefusesDespiteMatchingInventorySlots() {
        val c=chunk()
        c.getList("sections",10).getCompound(0).getCompound("block_states").getList("palette",10).getCompound(0).putString("Name","minecraft:air")
        val before=c.copy();assertFailsWith<IllegalArgumentException> { MaintenanceInventoryNbt.repair(c,block,images) };assertEquals(before,c)
    }
    @Test fun unsupportedOrSealedContainersAndDataVersionsRefuse() {
        val c=chunk();c.getList("block_entities",10).getCompound(0).putString("LootTable","minecraft:chests/simple_dungeon")
        assertFailsWith<IllegalArgumentException> { MaintenanceInventoryNbt.repair(c,block,images) }
        val wrong=chunk();wrong.putInt("DataVersion",1)
        assertFailsWith<IllegalArgumentException> { MaintenanceInventoryNbt.repair(wrong,block,images) }
    }
}
