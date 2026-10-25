package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.*
import com.bareminimumstudios.guardian.storage.jdbc.SqliteStorageBackend
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.nbt.*
import net.minecraft.util.DirectoryLock
import net.minecraft.world.item.*
import net.minecraft.world.level.Level
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.storage.*
import java.nio.file.*
import java.util.UUID
import kotlin.test.*

class ItemMaintenanceRecoveryTest {
    companion object { init { SharedConstants.tryDetectVersion();Bootstrap.bootStrap() };private val registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY) }
    private class Fixture(withPartialPlayer: Boolean=true, preparedOnly: Boolean=false) : AutoCloseable {
        val base=Files.createTempDirectory("guardian-maintenance-engine")
        val world=Files.createDirectory(base.resolve("world"))
        val lock=DirectoryLock.create(world)
        val db=SqliteStorageBackend(base.resolve("history.sqlite")).also { it.open() }
        val player=ItemSlotOwner.PlayerInventory(UUID.randomUUID())
        val block=ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"),BlockPosition(0,64,0))
        val item=MinecraftItemSnapshotter.capture(ItemStack(Items.COAL,9).apply { set(DataComponents.CUSTOM_NAME,Component.literal("Recovery components")) },registries)
        val empty=ItemStackSnapshot.EMPTY
        val a=ItemSlotAddress(player,0);val b=ItemSlotAddress(block,0)
        val original=InventorySnapshot(buildMap { for(i in 0..40)put(ItemSlotAddress(player,i),empty);for(i in 0..26)put(ItemSlotAddress(block,i),if(i==0)item else empty) })
        val record: ItemRollbackRecord
        val engine: ItemMaintenanceRecovery.Engine
        val playerFile=Files.createDirectories(world.resolve("playerdata")).resolve("${player.playerId}.dat")
        val regionFolder=Files.createDirectories(world.resolve("region"))
        val regionFile=regionFolder.resolve("r.0.0.mca")
        private fun stack()=MinecraftItemSnapshotter.decodeTag(item.itemData!!.copyBytes()).apply { putInt("count",9);putByte("Slot",0) }
        init {
            val row=ContainerTransactionSnapshot(UUID.randomUUID(),1,ActorIdentity.Player(player.playerId,"RecoveryTest"),0,ContainerAction.QUICK_MOVE,
                listOf(ItemSlotChange(a,item,empty),ItemSlotChange(b,empty,item)),listOf(block))
            db.append(listOf(ContainerAuditEntry(row)))
            record=db.prepareItemRollback(UUID.randomUUID(),2,listOf(row));check(db.protectItemRollback(record,original))
            if(!preparedOnly)check(db.transitionItemRollback(record.operationId,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING))
            val p=CompoundTag().apply {
                putInt("DataVersion",SharedConstants.getCurrentVersion().dataVersion.version);putUUID("UUID",player.playerId)
                putFloat("Health",17f);put("Inventory",ListTag().apply { if(withPartialPlayer)add(stack()) })
            }
            NbtIo.writeCompressed(p,playerFile)
            val chunk=CompoundTag().apply {
                putInt("DataVersion",SharedConstants.getCurrentVersion().dataVersion.version);putInt("xPos",0);putInt("zPos",0);putString("Status","minecraft:full")
                put("sections",ListTag().apply { add(CompoundTag().apply {
                    putByte("Y",4);put("block_states",CompoundTag().apply {
                        put("palette",ListTag().apply { add(CompoundTag().apply { putString("Name","minecraft:barrel") }) })
                    })
                }) })
                putString("Unrelated","keep unrelated chunk content")
                put("block_entities",ListTag().apply { add(CompoundTag().apply {
                    putString("id","minecraft:barrel");putInt("x",0);putInt("y",64);putInt("z",0)
                    put("Items",ListTag().apply { add(stack()) })
                }) })
            }
            region(regionFile) { it.getChunkDataOutputStream(ChunkPos(0,0)).use { output -> NbtIo.write(chunk,output) };it.flush() }
            engine=ItemMaintenanceRecovery.Engine(world,base.resolve("recovery"),db,lock)
        }
        fun region(path: Path=regionFile, action: (RegionFile) -> Unit) { RegionFile(RegionStorageInfo("test",Level.OVERWORLD,"test"),path,path.parent,true).use(action) }
        fun verifyExpected() {
            val images=db.itemRollbackImages(record.operationId)!!.images
            val p=NbtIo.readCompressed(playerFile,NbtAccounter.create(16L*1024*1024))
            assertEquals(17f,p.getFloat("Health"))
            assertEquals(images.expected.slots.filterKeys { it.owner==player },MinecraftSavedPlayerDecoder.decode(p,player,images.expected.slots.keys.filter { it.owner==player }.toSet(),registries).slots)
            region { file ->
                val chunk=file.getChunkDataInputStream(ChunkPos(0,0))!!.use { NbtIo.read(it,NbtAccounter.create(32L*1024*1024)) }
                assertEquals("keep unrelated chunk content",chunk.getString("Unrelated"))
                assertEquals(images.expected.slots.filterKeys { it.owner==block },MinecraftSavedContainerDecoder.decode(chunk,block,images.expected.slots.keys.filter { it.owner==block }.toSet(),registries).slots)
            }
        }
        override fun close() { db.close();lock.close() }
    }
    @Test fun unstartedProtectedOperationCancelsWithoutReadingOrCreatingMissingPlayerData() = Fixture(preparedOnly=true).use { f ->
        Files.delete(f.playerFile)
        val region=Files.readAllBytes(f.regionFile)
        val token=assertNotNull(f.engine.unstartedToken(f.record.operationId))
        f.engine.apply(f.record.operationId,token)
        assertEquals(ItemRollbackPhase.CANCELLED,f.db.itemRollback(f.record.operationId)!!.phase)
        assertFalse(Files.exists(f.playerFile));assertContentEquals(region,Files.readAllBytes(f.regionFile))
        assertTrue(f.db.unfinishedItemRollbacks(10).isEmpty());assertNull(f.engine.preview(f.record.operationId))
        val rows=f.db.lookupContainers(com.bareminimumstudios.guardian.storage.query.ContainerLookupQuery(limit=50))
        assertEquals(ItemRollbackPhase.PREPARED,f.db.prepareItemRollback(UUID.randomUUID(),3,rows).phase)
    }
    @Test fun applyingOperationCannotBeCancelledUsingItsStalePreparedHeader() = Fixture().use { f ->
        assertFalse(f.db.cancelUnstartedItemRollback(f.record))
        assertTrue(f.db.itemRollbackProtected(f.record.operationId))
        assertEquals(ItemRollbackPhase.APPLYING,f.db.itemRollback(f.record.operationId)!!.phase)
    }
    @Test fun exactPartialPlayerAndChunkImagesRepairAndAcknowledgeWithoutAWorldRestore() = Fixture().use { f ->
        val beforePlayer=Files.readAllBytes(f.playerFile);val beforeRegion=Files.readAllBytes(f.regionFile)
        val plan=assertNotNull(f.engine.preview(f.record.operationId))
        assertContentEquals(beforePlayer,Files.readAllBytes(f.playerFile));assertContentEquals(beforeRegion,Files.readAllBytes(f.regionFile))
        f.engine.apply(f.record.operationId,plan.token)
        assertTrue(f.db.itemRollbackImages(f.record.operationId)!!.acknowledged)
        assertTrue(f.db.unfinishedItemRollbacks(10).isEmpty());f.verifyExpected()
    }
    @Test fun interruptedRepairRetainsJournalThenResumesFromItsCommittedFilePlan() = Fixture(false).use { f ->
        val plan=assertNotNull(f.engine.preview(f.record.operationId))
        assertFailsWith<IllegalStateException> { f.engine.apply(f.record.operationId,plan.token) { error("interrupted after first changed file") } }
        assertFalse(f.db.itemRollbackImages(f.record.operationId)!!.acknowledged)
        assertTrue(f.db.itemRollbackProtected(f.record.operationId))
        val retry=assertNotNull(f.engine.preview(f.record.operationId));assertEquals(plan.token,retry.token)
        f.engine.apply(f.record.operationId,retry.token);f.verifyExpected()
    }
    @Test fun acknowledgedOperationNeverOverwritesLaterLegitimateFileChanges() = Fixture().use { f ->
        val plan=assertNotNull(f.engine.preview(f.record.operationId));f.engine.apply(f.record.operationId,plan.token)
        val p=NbtIo.readCompressed(f.playerFile,NbtAccounter.create(16L*1024*1024));p.putFloat("Health",10f);NbtIo.writeCompressed(p,f.playerFile)
        val later=Files.readAllBytes(f.playerFile)
        assertNull(f.engine.preview(f.record.operationId));f.engine.apply(f.record.operationId,plan.token)
        assertContentEquals(later,Files.readAllBytes(f.playerFile))
    }
    @Test fun unrelatedFileDriftAfterPreviewRefusesAndKeepsOriginalProtection() = Fixture().use { f ->
        val plan=assertNotNull(f.engine.preview(f.record.operationId))
        val p=NbtIo.readCompressed(f.playerFile,NbtAccounter.create(16L*1024*1024));p.putFloat("Health",10f);NbtIo.writeCompressed(p,f.playerFile)
        val region=Files.readAllBytes(f.regionFile)
        assertFailsWith<IllegalArgumentException> { f.engine.apply(f.record.operationId,plan.token) }
        assertContentEquals(region,Files.readAllBytes(f.regionFile));assertTrue(f.db.itemRollbackProtected(f.record.operationId))
    }
}
