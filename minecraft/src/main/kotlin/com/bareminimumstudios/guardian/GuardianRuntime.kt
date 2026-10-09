package com.bareminimumstudios.guardian

import com.bareminimumstudios.guardian.config.GuardianConfig
import com.bareminimumstudios.guardian.logging.BufferedLogPipeline
import com.bareminimumstudios.guardian.logging.bulk.BulkAuditDispatcher
import com.bareminimumstudios.guardian.integration.api.GuardianIntegrationApi
import com.bareminimumstudios.guardian.lookup.BlockHistoryService
import com.bareminimumstudios.guardian.permission.PermissionService
import com.bareminimumstudios.guardian.rollback.BlockRollbackService
import com.bareminimumstudios.guardian.storage.QueryableStorageBackend
import com.bareminimumstudios.guardian.storage.StorageBackendFactory
import java.nio.file.Path
import net.minecraft.server.MinecraftServer
import java.time.Duration

class GuardianRuntime(
    private val config: GuardianConfig,
    val permissions: PermissionService,
    private val storageRoot: Path
) {
    private var storage: QueryableStorageBackend? = null
    private var pipeline: BufferedLogPipeline? = null
    private var history: BlockHistoryService? = null
    private var bulk: BulkAuditDispatcher? = null
    private var rollback: BlockRollbackService? = null
    private var itemPreview: com.bareminimumstudios.guardian.rollback.ContainerRollbackPreviewService? = null

    private var inventoryCoordination: com.bareminimumstudios.guardian.rollback.ItemOwnerCoordination? = null

    private var itemOperations: com.bareminimumstudios.guardian.platform.minecraft.MinecraftItemOperations? = null
    private var managedRetentions = false

    private var shutdown: com.bareminimumstudios.guardian.storage.StorageShutdown? = null
    private var shutdownDrains: List<java.util.concurrent.CompletionStage<Void>>? = null
    private var shutdownClean = true

    private var itemRecovery: com.bareminimumstudios.guardian.rollback.ItemRecoveryService? = null

    fun start(server: MinecraftServer) {
        if (!config.general.enabled.get()) return

        check(storage == null && shutdownDrains == null) { "Prior runtime shutdown is not complete" }
        shutdown = null
        val queueCapacity = config.performance.queueCapacity.get()
        val configuredBatch = config.performance.batchSize.get()
        val batchSize = configuredBatch.coerceAtMost(queueCapacity)
        val selectedStorage = StorageBackendFactory.create(config.storage, storageRoot)

        val selectedPipeline = BufferedLogPipeline(
            storage = selectedStorage,
            queueCapacity = queueCapacity,
            batchSize = batchSize,
            flushIntervalMillis = config.performance.flushIntervalMillis.get().toLong(),
            closeStorageOnStop = false
        ).also { it.start() }

        val selectedBulk = BulkAuditDispatcher(
            pipeline = selectedPipeline,
            maxPendingOperations = config.integrations.worldEditMaxPendingOperations.get(),
            maxEntriesPerOperation = config.integrations.worldEditMaxChangesPerOperation.get()
        ).also { it.start() }
        val selectedHistory = BlockHistoryService(selectedStorage, server)
        storage = selectedStorage
        pipeline = selectedPipeline
        bulk = selectedBulk
        history = selectedHistory
        rollback = BlockRollbackService(server, selectedHistory, config)
        itemPreview = com.bareminimumstudios.guardian.rollback.ContainerRollbackPreviewService(server,selectedHistory,selectedPipeline) { itemRecovery?.isBusy()==true }
        itemRecovery = com.bareminimumstudios.guardian.rollback.ItemRecoveryService(server,selectedHistory,selectedPipeline) { itemPreview?.isBusy()==true }
        inventoryCoordination = com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination.install(server)
        itemOperations = (selectedStorage as? com.bareminimumstudios.guardian.rollback.ItemRollbackJournal)?.let { journal ->
            com.bareminimumstudios.guardian.platform.minecraft.MinecraftItemOperations(server, checkNotNull(inventoryCoordination), journal) {
                itemPreview?.isBusy() == true || itemRecovery?.isBusy() == true
            }
        }
        itemOperations?.let { com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination.attachOperations(server,it) }
        GuardianIntegrationApi.attach(config, selectedBulk)
        com.bareminimumstudios.guardian.platform.minecraft.PlayerContainerCapture.install({ pipeline }, {
            config.general.enabled.get() && config.logging.enabled.get() && config.logging.containerTransactions.get()
        })
        com.bareminimumstudios.guardian.platform.minecraft.HopperTransferCapture.install({ pipeline }, {
            config.general.enabled.get() && config.logging.enabled.get() && config.logging.containerTransactions.get() && config.logging.automatedContainerTransfers.get()
        })
    }

    fun stop(): Boolean {
        if (shutdownDrains != null) return finishShutdown()
        val itemDrain = itemOperations?.stopAndDrain()
        managedRetentions = itemOperations?.ownsAllRetentions() == true
        val diskDrain = com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination.beginShutdown(inventoryCoordination)
        com.bareminimumstudios.guardian.platform.minecraft.PlayerContainerCapture.install({ null }, { false })
        com.bareminimumstudios.guardian.platform.minecraft.HopperTransferCapture.install({ null }, { false })
        itemRecovery?.stop()
        itemRecovery=null
        itemPreview?.stop()
        itemPreview = null
        rollback?.stop()
        rollback = null
        GuardianIntegrationApi.detach()
        val bulkClean = bulk?.stopGracefully(Duration.ofSeconds(15)) ?: true
        bulk = null
        val activeHistory = history
        activeHistory?.stopAndAwait()
        history = null

        val active = pipeline
        val pipelineClean = active?.stopGracefully(Duration.ofSeconds(15)) ?: true
        pipeline = null
        shutdownClean = bulkClean && pipelineClean
        shutdownDrains = listOfNotNull(itemDrain, diskDrain, activeHistory?.drained, active?.drained)
        return finishShutdown()
    }

    /** Stop timeouts never grant authority to close a backend with active work. */
    private fun finishShutdown(): Boolean {
        if(inventoryCoordination?.hasJournalRetention() == true && !managedRetentions) return false
        val backend = storage
        if(shutdown == null && backend != null) {
            shutdown = com.bareminimumstudios.guardian.storage.StorageShutdown(checkNotNull(shutdownDrains)) {backend.close()}
        }
        try {shutdown?.closed?.toCompletableFuture()?.get(5L,java.util.concurrent.TimeUnit.SECONDS)}
        catch(_: Exception){return false}
        if(!com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination.tryUninstall(inventoryCoordination)) return false
        itemOperations = null
        inventoryCoordination = null
        storage = null
        shutdownDrains = null
        return shutdownClean
    }

    /** Both loaders use the same tick order and item lifecycle. */
    fun tick() {
        rollback?.tick()
        itemPreview?.tick()
        itemRecovery?.tick()
        itemOperations?.tick()
    }

    internal fun itemOperations() = itemOperations
    fun inventoryCoordination() = inventoryCoordination
    fun pipeline(): BufferedLogPipeline? = pipeline
    fun storage(): QueryableStorageBackend? = storage
    fun history(): BlockHistoryService? = history
    fun bulk(): BulkAuditDispatcher? = bulk
    fun rollback(): BlockRollbackService? = rollback
    fun itemRecovery() = itemRecovery
    fun itemRollbackPreview() = itemPreview
}
