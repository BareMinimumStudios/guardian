package com.bareminimumstudios.guardian.platform.minecraft

import net.minecraft.nbt.*
import java.nio.file.*
import java.util.*
import java.util.concurrent.*
import kotlin.test.*

class MinecraftSavedPlayerReaderTest {
    @Test fun readsOnlyCurrentUuidFileWithoutWritingOrUsingOldFallback() {
        val folder=Files.createTempDirectory("guardian-player-read");val id=UUID.randomUUID();val file=folder.resolve("$id.dat")
        val tag=CompoundTag().apply { putString("fixture","current") };NbtIo.writeCompressed(tag,file);val before=Files.readAllBytes(file)
        assertEquals(tag,MinecraftSavedPlayerReader.readFile(folder,id).get());assertContentEquals(before,Files.readAllBytes(file))
        Files.move(file,folder.resolve("$id.dat_old"));assertTrue(MinecraftSavedPlayerReader.readFile(folder,id).isEmpty)
    }
    @Test fun corruptEncodedAndDecodedOversizedFilesAreRejected() {
        val folder=Files.createTempDirectory("guardian-player-bounds");val id=UUID.randomUUID();val file=folder.resolve("$id.dat")
        Files.write(file,byteArrayOf(1,2,3));assertFails { MinecraftSavedPlayerReader.readFile(folder,id) }
        Files.write(file,ByteArray(16*1024*1024+1));assertFails { MinecraftSavedPlayerReader.readFile(folder,id) }
        NbtIo.writeCompressed(CompoundTag().apply { putByteArray("oversized",ByteArray(17*1024*1024)) },file);assertFails { MinecraftSavedPlayerReader.readFile(folder,id) }
    }
    @Test fun stoppedReaderFailsPendingWorkAndIgnoresLateCompletion() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val reader=MinecraftSavedPlayerReader { _,_ -> entered.countDown();try { release.await(2,TimeUnit.SECONDS) } catch(_: InterruptedException) { };Optional.of(CompoundTag()) }
        val running=reader.read(Path.of("unused"),UUID.randomUUID());assertTrue(entered.await(2,TimeUnit.SECONDS));val queued=reader.read(Path.of("unused"),UUID.randomUUID())
        reader.close();reader.close();release.countDown();assertTrue(running.isCompletedExceptionally);assertTrue(queued.isCompletedExceptionally);assertTrue(reader.read(Path.of("unused"),UUID.randomUUID()).isCompletedExceptionally)
    }
    @Test fun queueCapacityIsBoundedAndRejectsExcessWithoutBlockingCaller() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val reader=MinecraftSavedPlayerReader { _,_ -> entered.countDown();release.await(2,TimeUnit.SECONDS);Optional.empty() }
        try {
            val first=reader.read(Path.of("unused"),UUID.randomUUID());assertTrue(entered.await(2,TimeUnit.SECONDS))
            val queued=(1..4).map { reader.read(Path.of("unused"),UUID.randomUUID()) };assertTrue(reader.read(Path.of("unused"),UUID.randomUUID()).isCompletedExceptionally)
            assertFalse(first.isDone);assertTrue(queued.none { it.isDone });release.countDown();first.get(2,TimeUnit.SECONDS);queued.forEach { it.get(2,TimeUnit.SECONDS) }
        } finally { release.countDown();reader.close() }
    }
    @Test fun fileIoRunsOffCallerThreadAndCallbackCanScheduleAnotherRead() {
        val caller=Thread.currentThread();val reader=MinecraftSavedPlayerReader { _,_ -> assertNotSame(caller,Thread.currentThread());Optional.of(CompoundTag()) }
        try { reader.read(Path.of("unused"),UUID.randomUUID()).thenCompose { reader.read(Path.of("unused"),UUID.randomUUID()) }.get(2,TimeUnit.SECONDS) } finally { reader.close() }
    }
    @Test fun resultFailureOnCloseDoesNotPretendBlockedFileWorkDrained(){
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val returned=java.util.concurrent.atomic.AtomicBoolean()
        val reader=MinecraftSavedPlayerReader{_,_->entered.countDown();check(release.await(3,TimeUnit.SECONDS));returned.set(true);Optional.empty()}
        val result=reader.read(Path.of("unused"),UUID.randomUUID());assertTrue(entered.await(2,TimeUnit.SECONDS))
        try{reader.close();assertTrue(result.isCompletedExceptionally);assertFalse(reader.drained.toCompletableFuture().isDone);reader.drained.toCompletableFuture().cancel(false);assertFalse(returned.get())}
        finally{release.countDown();reader.drained.toCompletableFuture().get(2,TimeUnit.SECONDS)}
        assertTrue(returned.get())
    }
    @Test fun cancelledResultCannotCancelOrMaskActualFileWork(){
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val reader=MinecraftSavedPlayerReader{_,_->entered.countDown();check(release.await(3,TimeUnit.SECONDS));Optional.empty()}
        val result=reader.read(Path.of("unused"),UUID.randomUUID());assertTrue(entered.await(2,TimeUnit.SECONDS))
        try{result.cancel(false);reader.close();assertFalse(reader.drained.toCompletableFuture().isDone)}
        finally{release.countDown();reader.drained.toCompletableFuture().get(2,TimeUnit.SECONDS)}
    }
    @Test fun queuedReadsAreRemovedWithoutInvokingFileLoaderDuringClose(){
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val count=java.util.concurrent.atomic.AtomicInteger()
        val reader=MinecraftSavedPlayerReader{_,_->count.incrementAndGet();entered.countDown();check(release.await(3,TimeUnit.SECONDS));Optional.empty()}
        reader.read(Path.of("unused"),UUID.randomUUID());assertTrue(entered.await(2,TimeUnit.SECONDS));val queued=(1..4).map{reader.read(Path.of("unused"),UUID.randomUUID())}
        try{reader.close();assertTrue(queued.all{it.isCompletedExceptionally});assertFalse(reader.drained.toCompletableFuture().isDone)}
        finally{release.countDown();reader.drained.toCompletableFuture().get(2,TimeUnit.SECONDS)}
        assertEquals(1,count.get())
    }

}
