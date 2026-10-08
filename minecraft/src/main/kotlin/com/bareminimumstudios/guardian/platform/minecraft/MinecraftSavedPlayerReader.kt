package com.bareminimumstudios.guardian.platform.minecraft

import net.minecraft.nbt.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.Optional
import java.util.UUID
import java.util.concurrent.*

/** Bounded file reader. Does not save, log in, data-fix or fall back to a player's .dat_old file. */
class MinecraftSavedPlayerReader internal constructor(private val loader: (Path,UUID) -> Optional<CompoundTag> = ::readFile): AutoCloseable {
    private val gate=Any()
    private var closed=false
    private val pending=mutableSetOf<CompletableFuture<Optional<CompoundTag>>>()
    private val executor=ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,ArrayBlockingQueue(4),ThreadFactory { Thread(it,"Guardian-SavedPlayerReader").apply { isDaemon=true } })
    fun read(folder: Path,id: UUID): CompletableFuture<Optional<CompoundTag>> {
        val future=CompletableFuture<Optional<CompoundTag>>()
        var rejected=false
        synchronized(gate) {
            if(closed) rejected=true
            else {
                pending.add(future)
                try { executor.execute {
                    val result=runCatching { loader(folder,id) }
                    val publish=synchronized(gate) { pending.remove(future);!closed }
                    if(!publish) future.completeExceptionally(CancellationException("Saved player reader stopped"))
                    else result.fold({ future.complete(it) },{ future.completeExceptionally(it) })
                } } catch(_: RejectedExecutionException) { pending.remove(future);rejected=true }
            }
        }
        if(rejected) future.completeExceptionally(IllegalStateException("Saved player reader is closed or full"))
        return future
    }
    override fun close() {
        val futures=synchronized(gate) { closed=true;pending.toList().also { pending.clear() } }
        futures.forEach { it.completeExceptionally(CancellationException("Saved player reader stopped")) }
        executor.shutdownNow()
    }
    companion object {
        internal fun readFile(folder: Path,id: UUID): Optional<CompoundTag> {
            val file=folder.resolve("$id.dat")
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) return Optional.empty()
            val before=Files.readAttributes(file,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
            require(before.size()<=16L*1024*1024) { "Encoded player file exceeds budget" }
            val tag=Files.newInputStream(file).use { NbtIo.readCompressed(it,NbtAccounter.create(16L*1024*1024)) }
            val after=Files.readAttributes(file,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
            require(before.fileKey()==after.fileKey() && before.size()==after.size() && before.lastModifiedTime()==after.lastModifiedTime()) { "Player file changed during reading" }
            return Optional.of(tag)
        }
    }
}
