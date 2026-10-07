package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.storage.QueryableStorageBackend
import com.bareminimumstudios.guardian.storage.query.BlockLookupQuery
import com.bareminimumstudios.guardian.storage.query.BlockRollbackState
import com.bareminimumstudios.guardian.storage.query.StoredBlockChange
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serializes database-facing interactive work away from the server thread.
 * All completion callbacks are marshalled back onto Minecraft's server executor.
 */
class BlockHistoryService(
    private val storage: QueryableStorageBackend,
    private val server: MinecraftServer
) {
    private val logger = LoggerFactory.getLogger("Guardian/History")
    private val running = AtomicBoolean(true)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Guardian-History").apply { isDaemon = true }
    }

    fun lookup(
        query: BlockLookupQuery,
        onSuccess: (List<StoredBlockChange>) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        if (!running.get()) {
            server.execute { onFailure(IllegalStateException("Guardian history service is stopping")) }
            return
        }
        executor.execute {
            runCatching { storage.lookupBlocks(query) }
                .onSuccess { rows -> server.execute { onSuccess(rows) } }
                .onFailure { throwable ->
                    logger.error("Block history lookup failed", throwable)
                    server.execute { onFailure(throwable) }
                }
        }
    }

    fun lookupContainers(
        query: com.bareminimumstudios.guardian.storage.query.ContainerLookupQuery,
        onSuccess: (List<com.bareminimumstudios.guardian.domain.ContainerTransactionSnapshot>) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        if (!running.get()) { server.execute { onFailure(IllegalStateException("Guardian history service is stopping")) }; return }
        executor.execute {
            runCatching { storage.lookupContainers(query) }
                .onSuccess { rows -> server.execute { onSuccess(rows) } }
                .onFailure { error -> logger.error("Container history lookup failed", error); server.execute { onFailure(error) } }
        }
    }

    fun setRollbackState(
        rowIds: Collection<Long>,
        state: BlockRollbackState,
        onSuccess: (Int) -> Unit = {},
        onFailure: (Throwable) -> Unit = {}
    ) {
        if (rowIds.isEmpty()) {
            server.execute { onSuccess(0) }
            return
        }
        if (!running.get()) {
            server.execute { onFailure(IllegalStateException("Guardian history service is stopping")) }
            return
        }
        val immutableIds = rowIds.toList()
        executor.execute {
            runCatching { storage.setBlockRollbackState(immutableIds, state) }
                .onSuccess { changed -> server.execute { onSuccess(changed) } }
                .onFailure { throwable ->
                    logger.error("Failed to persist rollback state for {} block rows", immutableIds.size, throwable)
                    server.execute { onFailure(throwable) }
                }
        }
    }

    fun stopAndAwait(timeoutSeconds: Long = 5L) {
        if (!running.compareAndSet(true, false)) return
        executor.shutdown()
        if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
            executor.shutdownNow()
            executor.awaitTermination(1L, TimeUnit.SECONDS)
        }
    }
}
