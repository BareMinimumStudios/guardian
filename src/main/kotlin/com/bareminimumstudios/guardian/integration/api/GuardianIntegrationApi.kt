package com.bareminimumstudios.guardian.integration.api

import com.bareminimumstudios.guardian.config.GuardianConfig
import com.bareminimumstudios.guardian.logging.bulk.BulkCaptureSession
import com.bareminimumstudios.guardian.logging.bulk.BulkAuditDispatcher
import java.util.concurrent.atomic.AtomicReference

/**
 * Narrow optional-integration surface. It intentionally contains no WorldEdit types so the BML core does not link
 * against WorldEdit. Adapter jars register themselves at runtime.
 */
object GuardianIntegrationApi {
    private val configRef = AtomicReference<GuardianConfig?>()
    private val bulkRef = AtomicReference<BulkAuditDispatcher?>()
    private val regionProviderRef = AtomicReference<RegionSelectionProvider?>()

    internal fun attach(config: GuardianConfig, bulk: BulkAuditDispatcher) {
        configRef.set(config)
        bulkRef.set(bulk)
    }

    internal fun detach() {
        bulkRef.set(null)
    }

    fun worldEditLoggingEnabled(): Boolean =
        configRef.get()?.let { it.general.enabled.get() && it.logging.enabled.get() && it.integrations.worldEditLogging.get() } == true

    fun worldEditSelectionsEnabled(): Boolean =
        configRef.get()?.let { it.general.enabled.get() && it.integrations.worldEditSelections.get() } == true

    fun beginWorldEditCapture(source: String): BulkCaptureSession? {
        if (!worldEditLoggingEnabled()) return null
        return bulkRef.get()?.tryBegin(source)
    }

    fun registerRegionSelectionProvider(provider: RegionSelectionProvider) {
        check(regionProviderRef.compareAndSet(null, provider)) { "A region selection provider is already registered" }
    }

    fun unregisterRegionSelectionProvider(provider: RegionSelectionProvider) {
        regionProviderRef.compareAndSet(provider, null)
    }

    fun regionSelectionProvider(): RegionSelectionProvider? =
        if (worldEditSelectionsEnabled()) regionProviderRef.get() else null
}
