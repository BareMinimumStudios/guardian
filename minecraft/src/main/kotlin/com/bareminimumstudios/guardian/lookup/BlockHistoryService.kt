package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.domain.ContainerTransactionSnapshot
import com.bareminimumstudios.guardian.domain.EntityAuditEvent
import com.bareminimumstudios.guardian.domain.FluidAuditEvent
import com.bareminimumstudios.guardian.domain.PlayerActivity
import com.bareminimumstudios.guardian.export.ExportFiles
import com.bareminimumstudios.guardian.export.ExportKind
import com.bareminimumstudios.guardian.export.ExportQuery
import com.bareminimumstudios.guardian.rollback.ContainerHistoryGuard
import com.bareminimumstudios.guardian.rollback.ItemRecoveryRefusedException
import com.bareminimumstudios.guardian.rollback.ItemRestoreSource
import com.bareminimumstudios.guardian.rollback.ItemRollbackJournal
import com.bareminimumstudios.guardian.rollback.ItemRollbackPhase
import com.bareminimumstudios.guardian.rollback.ItemRollbackRecord
import com.bareminimumstudios.guardian.rollback.ItemRollbackSummary
import com.bareminimumstudios.guardian.storage.purge.AuditPurgePlan
import com.bareminimumstudios.guardian.storage.purge.AuditPurgeResult
import com.bareminimumstudios.guardian.storage.purge.PurgeScope
import com.bareminimumstudios.guardian.storage.query.ContainerLookupQuery
import com.bareminimumstudios.guardian.storage.query.EntityLookupQuery
import com.bareminimumstudios.guardian.storage.query.FluidLookupQuery
import com.bareminimumstudios.guardian.storage.query.PlayerActivityQuery
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicLong
import com.bareminimumstudios.guardian.checkpoint.*
import com.bareminimumstudios.guardian.logging.OperationTimings
import com.bareminimumstudios.guardian.logging.OperationTimings.Stage
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
    private val activityQueries=AtomicLong()
    private val activityFailures=AtomicLong()
    private val lastActivityNanos=AtomicLong()
    private val maxActivityNanos=AtomicLong()
    fun queryStatus():String="Activity queries: completed="+activityQueries.get()+" failed="+activityFailures.get()+" lastMs="+lastActivityNanos.get()/1_000_000+" maxMs="+maxActivityNanos.get()/1_000_000
    private val running = AtomicBoolean(true)
    private val termination = CompletableFuture<Void>()
    val drained: CompletionStage<Void> get() = termination.minimalCompletionStage()
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
        query: ContainerLookupQuery,
        onSuccess: (List<ContainerTransactionSnapshot>) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        if (!running.get()) { server.execute { onFailure(IllegalStateException("Guardian history service is stopping")) }; return }
        val queuedAt=System.nanoTime()
        executor.execute {
            OperationTimings.record(Stage.TRANSACTION_QUEUE,System.nanoTime()-queuedAt)
            val startedAt=System.nanoTime()
            val result=runCatching { storage.lookupContainers(query) }
            val finishedAt=System.nanoTime()
            OperationTimings.record(Stage.TRANSACTION_QUERY,finishedAt-startedAt)
            result.onSuccess { rows -> server.execute { OperationTimings.record(Stage.TRANSACTION_DELIVERY,System.nanoTime()-finishedAt);onSuccess(rows) } }
                .onFailure { error -> logger.error("Container history lookup failed", error); server.execute { onFailure(error) } }
        }
    }

    fun guardContainers(rows: List<ContainerTransactionSnapshot>, onSuccess: (ContainerHistoryGuard?) -> Unit, onFailure: (Throwable) -> Unit) {
        if (!running.get()) { server.execute { onFailure(IllegalStateException("Guardian history service is stopping")) }; return }
        val immutableRows=rows.toList()
        val queuedAt=System.nanoTime()
        executor.execute {
            OperationTimings.record(Stage.HISTORY_GUARD_QUEUE,System.nanoTime()-queuedAt)
            val startedAt=System.nanoTime()
            val result=runCatching { storage.guardContainerHistory(immutableRows) }
            OperationTimings.record(Stage.HISTORY_GUARD_QUERY,System.nanoTime()-startedAt)
            result
                .onSuccess { guard -> server.execute { onSuccess(guard) } }
                .onFailure { error -> logger.error("Container rollback history guard failed",error);server.execute { onFailure(error) } }
        }
    }

    fun lookupFluids(query: FluidLookupQuery, onSuccess: (List<FluidAuditEvent>) -> Unit, onFailure: (Throwable) -> Unit) {
        recoveryWork({ storage.lookupFluids(query) }, onSuccess, onFailure)
    }

    fun lookupEntities(query: EntityLookupQuery, onSuccess: (List<EntityAuditEvent>) -> Unit, onFailure: (Throwable) -> Unit) {
        recoveryWork({ storage.lookupEntities(query) }, onSuccess, onFailure)
    }

    fun lookupActivity(query: PlayerActivityQuery, onSuccess: (List<PlayerActivity>) -> Unit, onFailure: (Throwable) -> Unit) {
        recoveryWork({
            val start=System.nanoTime()
            try { storage.lookupActivity(query) } catch(error:Throwable) { activityFailures.incrementAndGet();throw error }
            finally { val elapsed=System.nanoTime()-start;lastActivityNanos.set(elapsed);maxActivityNanos.accumulateAndGet(elapsed,::maxOf);activityQueries.incrementAndGet() }
        }, onSuccess, onFailure)
    }

    fun exportRows(query: ExportQuery, permitted: () -> Boolean, onSuccess: (List<Any>) -> Unit, onFailure: (Throwable) -> Unit) = recoveryWork({
        check(permitted()) { "Export cancelled" };require(query.limit in 1..500);query.read(storage).toList().also { require(it.size<=query.limit) }
    },onSuccess,onFailure)

    fun exportFile(root: Path, kind: ExportKind, limit: Int, filters: String, requester: String, requestedAt: Long, scope: Map<String,Any?>, rows: List<Any>, permitted: () -> Boolean, onSuccess: (ExportFiles.Result) -> Unit, onFailure: (Throwable) -> Unit) = recoveryWork({
        ExportFiles.write(root,kind,limit,filters,requester,requestedAt,scope,rows,permitted)
    },onSuccess,onFailure)

    fun previewPurge(cutoff: Long, onSuccess: (AuditPurgePlan) -> Unit, onFailure: (Throwable) -> Unit) = recoveryWork({ storage.previewPurge(cutoff) },onSuccess,onFailure)
    fun previewPurge(cutoff: Long, scope: PurgeScope, onSuccess: (AuditPurgePlan) -> Unit, onFailure: (Throwable) -> Unit) = recoveryWork({ storage.previewPurge(cutoff,1000,scope) },onSuccess,onFailure)
    fun applyPurge(plan: AuditPurgePlan, permitted: () -> Boolean, onSuccess: (AuditPurgeResult) -> Unit, onFailure: (Throwable) -> Unit) = recoveryWork({ storage.applyPurge(plan,permitted) },onSuccess,onFailure)

    internal fun saveCheckpoint(value:InventoryCheckpoint,limits:InventoryCheckpointLimits,success:(InventoryCheckpointHeader)->Unit,failure:(Throwable)->Unit) =
        recoveryWork({ (storage as? InventoryCheckpointStore ?: error("Persistent checkpoints unavailable")).saveInventoryCheckpoint(value,limits) },success,failure)
    internal fun checkpoint(id:UUID,success:(InventoryCheckpoint?)->Unit,failure:(Throwable)->Unit) =
        recoveryWork({ (storage as? InventoryCheckpointStore ?: error("Persistent checkpoints unavailable")).inventoryCheckpoint(id) },success,failure)
    internal fun checkpointHeaders(player:UUID?,limit:Int,offset:Int,after:Long?,success:(List<InventoryCheckpointHeader>)->Unit,failure:(Throwable)->Unit) =
        recoveryWork({ (storage as? InventoryCheckpointStore ?: error("Persistent checkpoints unavailable")).inventoryCheckpoints(player,limit,offset,after) },success,failure)
    internal fun checkpointPlayers(name:String,success:(List<UUID>)->Unit,failure:(Throwable)->Unit) =
        recoveryWork({ (storage as? InventoryCheckpointStore ?: error("Persistent checkpoints unavailable")).inventoryCheckpointPlayers(name) },success,failure)
    internal fun checkpointRestoreSource(id:UUID,success:(CheckpointRestoreSource)->Unit,failure:(Throwable)->Unit) =
        recoveryWork({ (storage as? ItemRollbackJournal ?: error("Persistent item journal unavailable")).checkpointRestoreSource(id) },success,failure)
    internal fun prepareCheckpointRestore(id:UUID,before:InventoryCheckpoint,parent:UUID,requester:CheckpointRequester,limits:InventoryCheckpointLimits,success:(ItemRollbackRecord)->Unit,failure:(Throwable)->Unit) =
        recoveryWork({ (storage as? ItemRollbackJournal ?: error("Persistent item journal unavailable")).prepareCheckpointRestore(id,before.capturedAt,parent,before,requester,limits) },success,failure)

    fun recoveryRecord(id: UUID, onSuccess: (ItemRollbackRecord?) -> Unit, onFailure: (Throwable) -> Unit) {
        recoveryWork({ (storage as? ItemRollbackJournal)?.itemRollback(id) ?: throw IllegalStateException("Journal entry not found or persistent journal unavailable") },onSuccess,onFailure)
    }
    internal fun itemRestoreSource(id: UUID,onSuccess: (ItemRestoreSource)->Unit,onFailure:(Throwable)->Unit) =
        recoveryWork({ (storage as? ItemRollbackJournal ?: error("Persistent item journal unavailable")).itemRestoreSource(id) },onSuccess,onFailure)
    internal fun prepareItemRestore(id:UUID,time:Long,parent:UUID,onSuccess:(ItemRollbackRecord)->Unit,onFailure:(Throwable)->Unit) =
        recoveryWork({ (storage as? ItemRollbackJournal ?: error("Persistent item journal unavailable")).prepareItemRestore(id,time,parent) },onSuccess,onFailure)
    internal fun itemRestoreHeaders(onSuccess:(List<ItemRollbackSummary>)->Unit,onFailure:(Throwable)->Unit) =
        recoveryWork({ (storage as? ItemRollbackJournal ?: error("Persistent item journal unavailable")).restorableItemRollbacks(10) },onSuccess,onFailure)
    internal fun prepareCreativeRecovery(id: UUID,time: Long,rows: List<ContainerTransactionSnapshot>,
        onSuccess: (ItemRollbackRecord) -> Unit,onFailure: (Throwable) -> Unit) =
        recoveryWork({ (storage as? ItemRollbackJournal ?: error("Persistent item journal unavailable")).prepareCreativeRecovery(id,time,rows) },onSuccess,onFailure)
    internal fun prepareItemRollback(id: UUID, time: Long, rows: List<ContainerTransactionSnapshot>,
        onSuccess: (ItemRollbackRecord) -> Unit, onFailure: (Throwable) -> Unit) =
        recoveryWork({ (storage as? ItemRollbackJournal ?: error("Persistent item journal unavailable")).prepareItemRollback(id,time,rows) },onSuccess,onFailure)

    internal fun cancelPreparedItemRollback(id: UUID, onSuccess: (Boolean) -> Unit, onFailure: (Throwable) -> Unit) =
        recoveryWork({ (storage as? ItemRollbackJournal ?: error("Persistent item journal unavailable")).transitionItemRollback(id,
            ItemRollbackPhase.PREPARED,ItemRollbackPhase.CANCELLED) },onSuccess,onFailure)

    fun recoveryHeaders(onSuccess: (List<ItemRollbackSummary>) -> Unit,onFailure: (Throwable) -> Unit) {
        recoveryWork({ (storage as? ItemRollbackJournal)?.unfinishedItemRollbacks(10) ?: throw IllegalStateException("Persistent journal unavailable") },onSuccess,onFailure)
    }
    private fun <T> recoveryWork(task: () -> T,onSuccess: (T) -> Unit,onFailure: (Throwable) -> Unit) {
        if(!running.get()) { server.execute { onFailure(IllegalStateException("Guardian history service is stopping")) };return }
        executor.execute {
            runCatching(task).onSuccess { result -> server.execute { onSuccess(result) } }.onFailure { error -> if(error !is ItemRecoveryRefusedException && error !is InventoryCheckpointCapacityException) logger.warn("History task failed",error);server.execute { onFailure(error) } }
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
        Thread({
            try {
                while(!executor.awaitTermination(1L,TimeUnit.SECONDS)) { /* Actual executor termination, not the stop timeout. */ }
                termination.complete(null)
            } catch(error: InterruptedException){termination.completeExceptionally(error);Thread.currentThread().interrupt()}
        }, "Guardian-HistoryDrain").apply {isDaemon=true}.start()
        if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
            executor.shutdownNow()
            executor.awaitTermination(1L, TimeUnit.SECONDS)
        }
    }
}
