package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.mixin.*
import com.mojang.datafixers.util.Either
import net.minecraft.nbt.*
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.storage.ChunkStorage
import java.nio.file.Files
import java.util.Optional
import java.util.concurrent.CompletableFuture

/** Flushes queued writes, then reads region bytes on the owning I/O worker; never loads or saves a live chunk. */
object MinecraftSavedChunkReader {
    fun read(storage: ChunkStorage,pos: ChunkPos): CompletableFuture<Optional<CompoundTag>> {
        val worker=(storage as ChunkStorageAccessor).`guardian$worker`()
        return worker.synchronize(true).thenCompose {
            val access=worker as IOWorkerAccessor
            access.`guardian$readTask` {
                try {
                    val region=access.`guardian$storage`() as Any as RegionStorageAccessor
                    val path=region.`guardian$folder`().resolve("r.${pos.regionX}.${pos.regionZ}.mca")
                    if(!Files.isRegularFile(path)) Either.left(Optional.empty())
                    else {
                        val tag=region.`guardian$region`(pos).getChunkDataInputStream(pos)?.use { NbtIo.read(it,NbtAccounter.create(16L*1024*1024)) }
                        Either.left(Optional.ofNullable(tag))
                    }
                } catch(error: Exception) { Either.right(error) }
            }
        }
    }
}
