package com.bareminimumstudios.guardian.platform.minecraft

import net.minecraft.nbt.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.Optional
import java.util.UUID
import java.util.concurrent.*

/** Bounded file reader. Does not save, log in, data-fix or fall back to a player's .dat_old file. */
class MinecraftSavedPlayerReader internal constructor(private val actualIo: com.bareminimumstudios.guardian.rollback.ActualIoDrain = com.bareminimumstudios.guardian.rollback.ActualIoDrain(5),
    private val ownsActualIo: Boolean = true,
    private val loader: (Path,UUID) -> Optional<CompoundTag> = ::readFile): AutoCloseable {
    private val gate=Any()
    private var closed=false
    private val pending=linkedSetOf<Request>()
    private val executor=ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,ArrayBlockingQueue(4),ThreadFactory { Thread(it,"Guardian-SavedPlayerReader").apply { isDaemon=true } })
    val drained: CompletionStage<Void> get() = actualIo.drained
    fun read(folder: Path,id: UUID): CompletableFuture<Optional<CompoundTag>> {
        val future=CompletableFuture<Optional<CompoundTag>>()
        synchronized(gate) {
            if(closed) {future.completeExceptionally(IllegalStateException("Saved player reader is closed"));return future}
            val ticket=try {actualIo.begin()} catch(error: Exception){future.completeExceptionally(error);return future}
            val request=Request(folder,id,future,ticket)
            pending.add(request)
            try {executor.execute(request)} catch(error: RejectedExecutionException){pending.remove(request);ticket.close();future.completeExceptionally(error)}
        }
        return future
    }
    private inner class Request(val folder: Path,val id: UUID,val future: CompletableFuture<Optional<CompoundTag>>,
                                val ticket: com.bareminimumstudios.guardian.rollback.ActualIoDrain.Ticket): Runnable {
        var started=false
        override fun run() {
            synchronized(gate){if(this !in pending)return;started=true}
            try {
                val result=runCatching {loader(folder,id)}
                if(synchronized(gate){closed})future.completeExceptionally(CancellationException("Saved player reader stopped"))
                else result.fold({future.complete(it)},{future.completeExceptionally(it)})
            } finally {synchronized(gate){pending.remove(this)};ticket.close()}
        }
    }
    override fun close() {
        val waiting=synchronized(gate) {
            if(closed)return
            closed=true
            pending.toList().also { requests ->
                requests.filter {!it.started}.forEach {executor.remove(it);pending.remove(it);it.ticket.close()}
            }
        }
        // Result failure is immediate, but a started file read retains its physical ticket.
        waiting.forEach {it.future.completeExceptionally(CancellationException("Saved player reader stopped"))}
        executor.shutdown()
        if(ownsActualIo)actualIo.close()
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
