package com.bareminimumstudios.guardian

import com.bareminimumstudios.guardian.checkpoint.InventoryCheckpointLimits
import com.bareminimumstudios.guardian.checkpoint.InventoryCheckpointService
import com.bareminimumstudios.guardian.export.HistoryExportService
import com.bareminimumstudios.guardian.platform.minecraft.ActivitySpy
import com.bareminimumstudios.guardian.platform.minecraft.BlockMovementCapture
import com.bareminimumstudios.guardian.platform.minecraft.EntityHistoryCapture
import com.bareminimumstudios.guardian.platform.minecraft.EnvironmentHistoryCapture
import com.bareminimumstudios.guardian.platform.minecraft.FluidHistoryCapture
import com.bareminimumstudios.guardian.platform.minecraft.HopperTransferCapture
import com.bareminimumstudios.guardian.platform.minecraft.ItemFrameHistoryCapture
import com.bareminimumstudios.guardian.platform.minecraft.LecternHistoryCapture
import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination
import com.bareminimumstudios.guardian.platform.minecraft.MinecraftItemOperations
import com.bareminimumstudios.guardian.platform.minecraft.OpacStructureProtection
import com.bareminimumstudios.guardian.platform.minecraft.PlayerContainerCapture
import com.bareminimumstudios.guardian.platform.minecraft.SignHistoryCapture
import com.bareminimumstudios.guardian.purge.AuditPurgeService
import com.bareminimumstudios.guardian.rollback.ContainerRollbackPreviewService
import com.bareminimumstudios.guardian.rollback.ItemOwnerCoordination
import com.bareminimumstudios.guardian.rollback.ItemRecoveryService
import com.bareminimumstudios.guardian.rollback.ItemRollbackJournal
import com.bareminimumstudios.guardian.rollback.ItemRollbackStartupGuard
import com.bareminimumstudios.guardian.rollback.ItemRollbackStartupRefused
import com.bareminimumstudios.guardian.rollback.OnlineItemRollbackService
import com.bareminimumstudios.guardian.storage.StorageShutdown
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
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
    private var export: HistoryExportService? = null
    private var purge: AuditPurgeService? = null
    private var itemPreview: ContainerRollbackPreviewService? = null

    private var inventoryCoordination: ItemOwnerCoordination? = null

    private var itemOperations: MinecraftItemOperations? = null
    private var managedRetentions = false

    private var shutdown: StorageShutdown? = null
    private var shutdownDrains: List<CompletionStage<Void>>? = null
    private var shutdownClean = true

    private var checkpoints: InventoryCheckpointService? = null

    private var onlineItems: OnlineItemRollbackService? = null

    private var itemRecovery: ItemRecoveryService? = null

    fun start(server: MinecraftServer) {
        OpacStructureProtection.prepare(config.structureClaims)
        if (!config.general.enabled.get()) return

        check(storage == null && shutdownDrains == null) { "Prior runtime shutdown is not complete" }
        shutdown = null
        val queueCapacity = config.performance.queueCapacity.get()
        val configuredBatch = config.performance.batchSize.get()
        val batchSize = configuredBatch.coerceAtMost(queueCapacity)
        val selectedStorage = StorageBackendFactory.create(config.storage, storageRoot)

        val selectedPipeline = try { BufferedLogPipeline(
            storage = selectedStorage,
            queueCapacity = queueCapacity,
            batchSize = batchSize,
            flushIntervalMillis = config.performance.flushIntervalMillis.get().toLong(),
            closeStorageOnStop = false
        ).also { it.start {
            (selectedStorage as? ItemRollbackJournal)?.let {
                ItemRollbackStartupGuard.verify(it)
            }
        } } } catch (refusal: ItemRollbackStartupRefused) {
            // Fabric starts Guardian before vanilla world setup. Request a normal stop after setup
            // instead of throwing through the early lifecycle hook into incomplete world teardown.
            LoggerFactory.getLogger("Guardian").error(refusal.message)
            server.halt(false)
            return
        }

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
        export = HistoryExportService(selectedHistory,permissions,storageRoot)
        rollback = BlockRollbackService(server, selectedHistory, config, permissions)
        purge = AuditPurgeService(selectedHistory,permissions) {
            rollback?.isBusy()==true || itemPreview?.isBusy()==true || itemRecovery?.isBusy()==true || onlineItems?.isBusy()==true || inventoryCoordination?.hasReservations()==true
        }
        rollback?.installMaintenanceGate { purge?.isBusy()==true || onlineItems?.isBusy()==true }
        itemPreview = ContainerRollbackPreviewService(server,selectedHistory,selectedPipeline,permissions) { itemRecovery?.isBusy()==true || purge?.isBusy()==true || onlineItems?.isBusy()==true }
        itemRecovery = ItemRecoveryService(server,selectedHistory,selectedPipeline,permissions) { itemPreview?.isBusy()==true || purge?.isBusy()==true || onlineItems?.blocksObservation()==true }
        inventoryCoordination = MinecraftInventoryCoordination.install(server)
        itemOperations = (selectedStorage as? ItemRollbackJournal)?.let { journal ->
            MinecraftItemOperations(server, checkNotNull(inventoryCoordination), journal) {
                itemPreview?.isBusy() == true || itemRecovery?.isBusy() == true || purge?.isBusy()==true
            }
        }
        itemOperations?.let { MinecraftInventoryCoordination.attachOperations(server,it) }
        onlineItems = itemOperations?.let { operations ->
            OnlineItemRollbackService(server,selectedHistory,selectedPipeline,permissions,operations) {
                itemPreview?.isBusy()==true || itemRecovery?.isBusy()==true || purge?.isBusy()==true || rollback?.isBusy()==true
            }
        }
        checkpoints = InventoryCheckpointService(
            server,selectedHistory,permissions,config.inventoryBackups.automatic.get(),config.inventoryBackups.intervalSeconds.get(),
            InventoryCheckpointLimits(config.inventoryBackups.perPlayer.get(),config.inventoryBackups.total.get(),config.inventoryBackups.maxMiB.get().toLong()*1024*1024),
            { config.general.enabled.get() && config.logging.enabled.get() },
            { onlineItems?.isBusy()==true || itemPreview?.isBusy()==true || itemRecovery?.isBusy()==true || purge?.isBusy()==true || rollback?.isBusy()==true || inventoryCoordination?.hasReservations()==true }
        )
        FluidHistoryCapture.install({ pipeline }, { config })
        EnvironmentHistoryCapture.install({ pipeline }, { config })
        SignHistoryCapture.install({ pipeline }, { config })
        LecternHistoryCapture.install({ pipeline }, { config })
        BlockMovementCapture.install({ pipeline }, { config })
        ItemFrameHistoryCapture.install({ pipeline }, { config })
        EntityHistoryCapture.install({ pipeline }, { config })
        ActivitySpy.install(server,config,permissions)
        GuardianIntegrationApi.attach(config, selectedBulk)
        PlayerContainerCapture.install({ pipeline }, {
            config.general.enabled.get() && config.logging.enabled.get() && config.logging.containerTransactions.get()
        })
        HopperTransferCapture.install({ pipeline }, {
            config.general.enabled.get() && config.logging.enabled.get() && config.logging.containerTransactions.get() && config.logging.automatedContainerTransfers.get()
        })

    }

    /** Optional integrations need other mods and all world registries to be ready. */
    fun serverStarted(server: MinecraftServer) {
        OpacStructureProtection.start(server,config.structureClaims,permissions)
    }

    fun stop(): Boolean {
        if (shutdownDrains != null) return finishShutdown()
        OpacStructureProtection.stop()
        FluidHistoryCapture.install({ null }, { null })
        EnvironmentHistoryCapture.install({ null }, { null })
        SignHistoryCapture.install({ null }, { null })
        LecternHistoryCapture.install({ null }, { null })
        BlockMovementCapture.install({ null }, { null })
        ItemFrameHistoryCapture.install({ null }, { null })
        EntityHistoryCapture.install({ null }, { null })
        ActivitySpy.clear()
        export?.stop()
        export=null
        purge?.stop()
        checkpoints?.stop()
        checkpoints=null
        onlineItems?.stop()
        val itemDrain = itemOperations?.stopAndDrain()
        managedRetentions = itemOperations?.ownsAllRetentions() == true
        val diskDrain = MinecraftInventoryCoordination.beginShutdown(inventoryCoordination)
        PlayerContainerCapture.install({ null }, { false })
        HopperTransferCapture.install({ null }, { false })
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
            shutdown = StorageShutdown(checkNotNull(shutdownDrains)) {backend.close()}
        }
        try {shutdown?.closed?.toCompletableFuture()?.get(5L,TimeUnit.SECONDS)}
        catch(_: Exception){return false}
        if(!MinecraftInventoryCoordination.tryUninstall(inventoryCoordination)) return false
        onlineItems = null
        itemOperations = null
        inventoryCoordination = null
        storage = null
        shutdownDrains = null
        return shutdownClean
    }

    /** Both loaders use the same tick order and item lifecycle. */
    fun tick() {
        OpacStructureProtection.tick()
        BlockMovementCapture.tick()
        export?.tick()
        purge?.tick()
        rollback?.tick()
        itemPreview?.tick()
        itemRecovery?.tick()
        itemOperations?.tick()
        onlineItems?.tick()
        checkpoints?.tick()
    }

    internal fun checkpoints() = checkpoints
    internal fun onlineItems() = onlineItems
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
