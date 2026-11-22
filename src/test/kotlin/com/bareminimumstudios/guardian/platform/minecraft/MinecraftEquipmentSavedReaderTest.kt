package com.bareminimumstudios.guardian.platform.minecraft

import net.minecraft.nbt.*
import java.io.DataOutputStream
import java.nio.file.*
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class MinecraftEquipmentSavedReaderTest {
    private fun cosmetic(path: Path,tag: CompoundTag) {
        Files.newOutputStream(path).use { stream -> DataOutputStream(stream).use { NbtIo.write(tag,it) } }
    }

    @Test fun readsBothActualFilesWithoutSavingOrFallingBackToOldData() {
        val folder=Files.createTempDirectory("guardian-equipment-reader");val id=UUID.randomUUID()
        val player=CompoundTag().apply { putString("fixture","player") }
        val armor=CompoundTag().apply { putString("fixture","separate armor") }
        val playerFile=folder.resolve("$id.dat");val armorFile=folder.resolve("$id.cosarmor")
        NbtIo.writeCompressed(player,playerFile);cosmetic(armorFile,armor)
        val playerBytes=Files.readAllBytes(playerFile);val armorBytes=Files.readAllBytes(armorFile)
        MinecraftSavedPlayerReader().use { reader ->
            val result=reader.readEquipment(folder,id,true).get(2,TimeUnit.SECONDS).orElseThrow()
            assertEquals(player,result.player);assertEquals(armor,result.cosmeticArmor)
            assertNull(reader.readEquipment(folder,id,false).get(2,TimeUnit.SECONDS).orElseThrow().cosmeticArmor)
        }
        assertContentEquals(playerBytes,Files.readAllBytes(playerFile));assertContentEquals(armorBytes,Files.readAllBytes(armorFile))
        Files.move(playerFile,folder.resolve("$id.dat_old"))
        assertTrue(MinecraftSavedPlayerReader.readEquipmentFiles(folder,id,true).isEmpty)
    }

    @Test fun requiredSidecarMissingCorruptOrOverBudgetRefusesTheWholeRead() {
        val folder=Files.createTempDirectory("guardian-equipment-reader-bounds");val id=UUID.randomUUID()
        NbtIo.writeCompressed(CompoundTag(),folder.resolve("$id.dat"))
        val armor=folder.resolve("$id.cosarmor")
        assertFails { MinecraftSavedPlayerReader.readEquipmentFiles(folder,id,true) }
        Files.write(armor,byteArrayOf(1,2,3))
        assertFails { MinecraftSavedPlayerReader.readEquipmentFiles(folder,id,true) }
        Files.write(armor,ByteArray(16*1024*1024+1))
        assertFails { MinecraftSavedPlayerReader.readEquipmentFiles(folder,id,true) }
        cosmetic(armor,CompoundTag().apply { putByteArray("oversized",ByteArray(17*1024*1024)) })
        assertFails { MinecraftSavedPlayerReader.readEquipmentFiles(folder,id,true) }
        assertTrue(MinecraftSavedPlayerReader.readEquipmentFiles(folder,id,false).isPresent)
    }

    @Test fun cancelledCombinedResultRetainsPhysicalIoUntilBothFileWorkHasReturned() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val caller=Thread.currentThread()
        val reader=MinecraftSavedPlayerReader(equipmentLoader={_,_,_->
            assertNotSame(caller,Thread.currentThread());entered.countDown()
            check(release.await(3,TimeUnit.SECONDS))
            Optional.of(MinecraftSavedPlayerReader.EquipmentSavedDocuments(CompoundTag(),CompoundTag()))
        })
        val result=reader.readEquipment(Path.of("unused"),UUID.randomUUID(),true)
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS));result.cancel(false);reader.close()
            assertFalse(reader.drained.toCompletableFuture().isDone)
            assertTrue(reader.readEquipment(Path.of("unused"),UUID.randomUUID(),true).isCompletedExceptionally)
        } finally { release.countDown();reader.close();reader.drained.toCompletableFuture().get(2,TimeUnit.SECONDS) }
        assertTrue(result.isCancelled)
    }

    @Test fun normalAndEquipmentReadsShareOneBoundedQueueAndCloseDoesNotRunQueuedLoads() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val loads=AtomicInteger()
        val reader=MinecraftSavedPlayerReader(equipmentLoader={_,_,_->
            loads.incrementAndGet();entered.countDown();check(release.await(3,TimeUnit.SECONDS))
            Optional.empty()
        },loader={_,_->loads.incrementAndGet();Optional.empty()})
        val first=reader.readEquipment(Path.of("unused"),UUID.randomUUID(),true)
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            val queued=listOf(reader.read(Path.of("unused"),UUID.randomUUID()),
                reader.readEquipment(Path.of("unused"),UUID.randomUUID(),true),
                reader.read(Path.of("unused"),UUID.randomUUID()),
                reader.readEquipment(Path.of("unused"),UUID.randomUUID(),false))
            assertTrue(reader.readEquipment(Path.of("unused"),UUID.randomUUID(),true).isCompletedExceptionally)
            assertTrue(reader.read(Path.of("unused"),UUID.randomUUID()).isCompletedExceptionally)
            assertTrue(queued.none { it.isDone });reader.close()
            assertTrue(first.isCompletedExceptionally);assertTrue(queued.all { it.isCompletedExceptionally })
            assertFalse(reader.drained.toCompletableFuture().isDone)
        } finally { release.countDown();reader.close();reader.drained.toCompletableFuture().get(2,TimeUnit.SECONDS) }
        assertEquals(1,loads.get())
    }
}
