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
    private var export: com.bareminimumstudios.guardian.export.HistoryExportService? = null
    private var purge: com.bareminimumstudios.guardian.purge.AuditPurgeService? = null
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
        export = com.bareminimumstudios.guardian.export.HistoryExportService(selectedHistory,permissions,storageRoot)
        rollback = BlockRollbackService(server, selectedHistory, config, permissions)
        purge = com.bareminimumstudios.guardian.purge.AuditPurgeService(selectedHistory,permissions) {
            rollback?.isBusy()==true || itemPreview?.isBusy()==true || itemRecovery?.isBusy()==true || inventoryCoordination?.hasReservations()==true
        }
        rollback?.installMaintenanceGate { purge?.isBusy()==true }
        itemPreview = com.bareminimumstudios.guardian.rollback.ContainerRollbackPreviewService(server,selectedHistory,selectedPipeline,permissions) { itemRecovery?.isBusy()==true || purge?.isBusy()==true }
        itemRecovery = com.bareminimumstudios.guardian.rollback.ItemRecoveryService(server,selectedHistory,selectedPipeline,permissions) { itemPreview?.isBusy()==true || purge?.isBusy()==true }
        inventoryCoordination = com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination.install(server)
        itemOperations = (selectedStorage as? com.bareminimumstudios.guardian.rollback.ItemRollbackJournal)?.let { journal ->
            com.bareminimumstudios.guardian.platform.minecraft.MinecraftItemOperations(server, checkNotNull(inventoryCoordination), journal) {
                itemPreview?.isBusy() == true || itemRecovery?.isBusy() == true || purge?.isBusy()==true
            }
        }
        itemOperations?.let { com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination.attachOperations(server,it) }
        com.bareminimumstudios.guardian.platform.minecraft.FluidHistoryCapture.install({ pipeline }, { config })
        com.bareminimumstudios.guardian.platform.minecraft.EnvironmentHistoryCapture.install({ pipeline }, { config })
        com.bareminimumstudios.guardian.platform.minecraft.SignHistoryCapture.install({ pipeline }, { config })
        com.bareminimumstudios.guardian.platform.minecraft.BlockMovementCapture.install({ pipeline }, { config })
        com.bareminimumstudios.guardian.platform.minecraft.ItemFrameHistoryCapture.install({ pipeline }, { config })
        com.bareminimumstudios.guardian.platform.minecraft.EntityHistoryCapture.install({ pipeline }, { config })
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
        com.bareminimumstudios.guardian.platform.minecraft.FluidHistoryCapture.install({ null }, { null })
        com.bareminimumstudios.guardian.platform.minecraft.EnvironmentHistoryCapture.install({ null }, { null })
        com.bareminimumstudios.guardian.platform.minecraft.SignHistoryCapture.install({ null }, { null })
        com.bareminimumstudios.guardian.platform.minecraft.BlockMovementCapture.install({ null }, { null })
        com.bareminimumstudios.guardian.platform.minecraft.ItemFrameHistoryCapture.install({ null }, { null })
        com.bareminimumstudios.guardian.platform.minecraft.EntityHistoryCapture.install({ null }, { null })
        export?.stop()
        export=null
        purge?.stop()
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
        purge = null

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
        com.bareminimumstudios.guardian.platform.minecraft.BlockMovementCapture.tick()
        export?.tick()
        purge?.tick()
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
    fun export() = export
    fun purge() = purge
    fun itemRecovery() = itemRecovery
    fun itemRollbackPreview() = itemPreview
}
