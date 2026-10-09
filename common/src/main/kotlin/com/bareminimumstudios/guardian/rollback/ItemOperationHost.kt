package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import java.util.concurrent.CompletionStage

/** Only a trusted platform may supply exclusion. Closing releases resources, never mutates items. */
interface ItemOperationPort : ItemApplyPort, ItemSavePort, AutoCloseable

enum class ItemOperationState { PROTECTING, APPLYING, SAVING, ACKNOWLEDGING, CONFIRMING, RECONCILING, DRAINING, COMPLETED, RECOVERY_REQUIRED }

/**
 * Serialized internal host. No commands construct it. Polls never wait for worker or disk I/O.
 * An unresolved operation retains owner protection after drain for explicit reconciliation.
 * It never retries writes, undoes partial changes or treats a late commit as cancellation.
 */
class ItemOperationHost private constructor(
    private val lease: ItemOwnerCoordination.Lease,
    private val worker: ItemRollbackJournalWorker,
    private val port: ItemOperationPort,
    private val retention: ItemJournalRetention,
    record: ItemRollbackRecord,
    original: InventorySnapshot,
    private val clock: () -> Long,
    private val protected: Boolean
) : AutoCloseable {
    private val thread = Thread.currentThread()
    private val owners = lease.owners
    private val plan = record
    private val fullImages = if (protected) ItemRollbackImages(record, original) else null
    private val saved = linkedMapOf<ItemSlotAddress, ItemStackSnapshot>()
    private var protection: java.util.concurrent.CompletableFuture<Boolean>? = null
    private var acknowledgment: java.util.concurrent.CompletableFuture<Boolean>? = null
    private var receipt: java.util.concurrent.CompletableFuture<ItemRollbackImageRecord?>? = null
    private var recoveryWorker: ItemRollbackJournalWorker? = null
    private var recovery: ItemReconciliationDriver? = null
    private var recoveryPort: ItemReconciliationPort? = null
    private var recoveryResolved = false
    private var stopGeneration = 0
    private val started = clock()
    private val virtual = original.slots.toMutableMap()
    private var closedPort = false
    private var advancing = false
    private var confirmed = false
    var state = if (protected) ItemOperationState.PROTECTING else ItemOperationState.APPLYING; private set
    var reason: String? = null; private set
    private val applyPort = object : ItemApplyPort {
        override fun isExclusiveAndCurrent(owners: Set<ItemSlotOwner>) = owners == this@ItemOperationHost.owners && current()
        override fun readOwners(owners: Set<ItemSlotOwner>): InventorySnapshot? =
            if (isExclusiveAndCurrent(owners)) image()?.takeIf { it.slots == virtual } else null
        override fun writeSlot(address: ItemSlotAddress, expected: ItemStackSnapshot, replacement: ItemStackSnapshot) {
            check(current() && virtual[address] == expected)
            port.writeSlot(address, expected, replacement)
            virtual[address] = replacement
        }
    }
    private val pendingSaved = linkedMapOf<ItemSlotOwner, CompletionStage<InventorySnapshot?>>()
    private val savePort = object : ItemSavePort {
        override fun isExclusiveAndCurrent(owners: Set<ItemSlotOwner>) =
            owners == this@ItemOperationHost.owners && current() && image()?.slots == virtual
        override fun saveAndReadBack(owner: ItemSlotOwner, addresses: Set<ItemSlotAddress>): CompletionStage<InventorySnapshot?> {
            check(isExclusiveAndCurrent(owners) && owner in owners)
            val expected = virtual.filterKeys { it.owner == owner }
            check(addresses.all { it in expected })
            // Pure immutable comparison may run on a worker; it makes no platform calls.
            return port.saveAndReadBack(owner, addresses).thenApply { image ->
                image?.takeIf { it.slots == expected }
            }.also { result -> pendingSaved[owner] = result }
        }
    }
    private val apply = ItemApplyDriver(record, worker, applyPort, clock)
    private var save: AsyncItemSaveCompletion? = null
    val completionAttempt: CompletionStage<Boolean>? get() { checkThread(); return save?.completionAttempt }
    val isJournalDrained: Boolean get() { checkThread(); return retention.isDrained && (recoveryWorker == null || recoveryWorker!!.drained.toCompletableFuture().isDone) }

    fun advance(): ItemOperationState {
        checkThread()
        check(!advancing) { "Item operation polling is not reentrant" }
        if (state == ItemOperationState.COMPLETED || state == ItemOperationState.RECOVERY_REQUIRED) return state
        advancing = true
        try {
            if (state in setOf(ItemOperationState.PROTECTING, ItemOperationState.ACKNOWLEDGING, ItemOperationState.CONFIRMING) &&
                clock() - started > java.util.concurrent.TimeUnit.SECONDS.toNanos(10)) {
                drain(false, "Durable admission or acknowledgment timed out")
                return state
            }
            when (state) {
                ItemOperationState.PROTECTING -> {
                    if (!current() || image()?.slots != virtual) {
                        drain(false, "Full inventory identity changed before durable admission")
                    } else {
                        if (protection == null) protection = worker.protect(plan, checkNotNull(fullImages).original).toCompletableFuture()
                        if (state != ItemOperationState.PROTECTING) return state
                        if (protection!!.isDone) {
                            if (!protection!!.join()) drain(false, "Complete durable protection was refused")
                            else if (current() && state == ItemOperationState.PROTECTING && image()?.slots == virtual && state == ItemOperationState.PROTECTING) state = ItemOperationState.APPLYING
                            else drain(false, "Ownership changed during durable admission")
                        }
                    }
                }
                ItemOperationState.ACKNOWLEDGING -> {
                    if (acknowledgment == null) {
                        check(current() && image()?.slots == checkNotNull(fullImages).expected.slots)
                        for ((_, result) in pendingSaved) saved.putAll(checkNotNull(result.toCompletableFuture().join()).slots)
                        check(saved == fullImages.expected.slots && state == ItemOperationState.ACKNOWLEDGING)
                        val completed = ItemRollbackRecord(plan.operationId, plan.createdAt, ItemRollbackPhase.COMPLETED, plan.entries)
                        acknowledgment = worker.acknowledge(completed, fullImages, InventorySnapshot(saved)).toCompletableFuture()
                    }
                    if (acknowledgment!!.isDone) {
                        check(acknowledgment!!.join()) { "Persistent acknowledgment refused" }
                        receipt = worker.readImages(plan.operationId).toCompletableFuture()
                        state = ItemOperationState.CONFIRMING
                    }
                }
                ItemOperationState.CONFIRMING -> if (receipt!!.isDone) {
                    val value = checkNotNull(receipt!!.join())
                    check(value.acknowledged && value.images.record.phase == ItemRollbackPhase.COMPLETED &&
                        sameRollbackImages(checkNotNull(fullImages), value.images))
                    drain(true, null)
                }
                ItemOperationState.RECONCILING -> {
                    val result = checkNotNull(recovery).advance()
                    if (state != ItemOperationState.RECONCILING) return state
                    if (result in setOf(ItemReconciliationState.RESOLVED, ItemReconciliationState.ALREADY_RESOLVED, ItemReconciliationState.UNRESOLVED)) {
                        recoveryResolved = result != ItemReconciliationState.UNRESOLVED
                        recoveryWorker!!.close()
                        state = ItemOperationState.DRAINING
                    }
                }
                ItemOperationState.APPLYING -> {
                    val result = apply.advance()
                    if (state != ItemOperationState.APPLYING) return state
                    when (result) {
                        ItemApplyState.UNRESOLVED -> drain(false, apply.reason ?: "Apply outcome is unresolved")
                        ItemApplyState.WRITTEN -> {
                            check(current() && image()?.slots == virtual)
                            save = AsyncItemSaveCompletion(checkNotNull(apply.applyingRecord), worker, savePort, clock)
                            state = ItemOperationState.SAVING
                        }
                        else -> Unit
                    }
                }
                ItemOperationState.SAVING -> {
                    val completion = checkNotNull(save)
                    val result = completion.advance()
                    if (state != ItemOperationState.SAVING) return state
                    when (result) {
                        AsyncItemSaveState.COMPLETED -> if (protected) state = ItemOperationState.ACKNOWLEDGING else drain(true, null)
                        AsyncItemSaveState.UNRESOLVED -> drain(false, completion.reason ?: "Save outcome is unresolved")
                        else -> Unit
                    }
                }
                ItemOperationState.DRAINING -> finishDrain()
                else -> Unit
            }
        } catch (_: Exception) { drain(false, "Operation failed; persistent outcome requires reconciliation") }
        finally { advancing = false }
        return state
    }

    /** Nonblocking stop. Started I/O can still finish, and no protected future is cancelled. */
    override fun close() {
        checkThread()
        if (state == ItemOperationState.COMPLETED || state == ItemOperationState.RECOVERY_REQUIRED) return
        stopGeneration++
        drain(false, "Operation stopped; persistent outcome requires reconciliation")
    }

    private fun drain(success: Boolean, message: String?) {
        recovery?.stop()
        recoveryWorker?.close()
        recoveryResolved = false
        confirmed = success
        state = ItemOperationState.DRAINING
        if (message != null) reason = message
        if (!success) {
            // Revoke before callbacks. The explicit barrier keeps owner entries attached.
            lease.close()
            apply.stop()
            save?.stop()
            closePort()
        }
        worker.close()
    }

    private fun finishDrain() {
        if (!isJournalDrained) return
        if (recoveryWorker != null) {
            val resolved = recoveryResolved
            val generation = stopGeneration
            recoveryResolved = false
            try { (recoveryPort as? AutoCloseable)?.close() }
            catch (_: Exception) { reason = "Reconciliation resource closure failed"; state = ItemOperationState.RECOVERY_REQUIRED; return }
            recoveryPort = null
            // A stop from resource closure must not turn an unresolved outcome into release.
            if (resolved && generation == stopGeneration && state == ItemOperationState.DRAINING && recovery?.state in setOf(ItemReconciliationState.RESOLVED, ItemReconciliationState.ALREADY_RESOLVED)) {
                lease.close()
                retention.releaseAfterReconciliation()
                state = ItemOperationState.COMPLETED
            } else state = ItemOperationState.RECOVERY_REQUIRED
            return
        }
        if (confirmed && current() && image()?.slots == virtual) {
            closePort()
            if (confirmed && lease.isCurrent(owners)) {
                lease.close()
                state = ItemOperationState.COMPLETED
                retention.releaseAfterReconciliation()
                return
            }
        }
        confirmed = false
        lease.close()
        closePort()
        reason = reason ?: "Ownership or contents changed during drain; persistent outcome requires reconciliation"
        state = ItemOperationState.RECOVERY_REQUIRED
        // Deliberately keep the barrier closed. A future reconciliation host must prove the outcome.
    }

    /** Read-only recovery of retained owners. The platform must prove physical quiescence. */
    fun reconcile(record: ItemRollbackRecord, journal: ItemRollbackJournal, readOnlyPort: ItemReconciliationPort,
                  actualDiskDrain: CompletionStage<Void>) {
        checkThread()
        check(!advancing && protected && state == ItemOperationState.RECOVERY_REQUIRED && isJournalDrained)
        require(record.phase == ItemRollbackPhase.COMPLETED || record.phase == ItemRollbackPhase.RECOVERY_REQUIRED)
        require(sameRollbackImages(checkNotNull(fullImages), ItemRollbackImages(record, fullImages.original)))
        val disk = actualDiskDrain.toCompletableFuture()
        check(disk.isDone) { "Actual disk work must drain before reconciliation" }
        disk.join()
        val next = ItemRollbackJournalWorker(journal)
        recoveryWorker = next
        recoveryPort = readOnlyPort
        recovery = ItemReconciliationDriver(record, next, readOnlyPort, clock)
        recoveryResolved = false
        state = ItemOperationState.RECONCILING
    }

    private fun closePort() {
        if (closedPort) return
        closedPort = true
        try { port.close() } catch (_: Exception) {
            confirmed = false
            reason = "Inventory resource closure failed; persistent outcome requires reconciliation"
        }
    }
    private fun current() = !closedPort && lease.isCurrent(owners) && port.isExclusiveAndCurrent(owners)
    private fun image() = port.readOwners(owners)?.takeIf { validImage(it, owners) }
    private fun checkThread() = check(Thread.currentThread() === thread) { "Item operation host must use its owning thread" }

    companion object {
        /** The prepared journal already exists. Failure here does not cancel its durable claims. */
        fun start(record: ItemRollbackRecord, coordination: ItemOwnerCoordination, journal: ItemRollbackJournal,
                  bind: (ItemOwnerCoordination.Lease) -> ItemOperationPort,
                  clock: () -> Long = System::nanoTime, requireDurableImages: Boolean = false): ItemOperationHost? {
            require(record.phase == ItemRollbackPhase.PREPARED)
            val owners = ItemRecoveryCheck(record).owners.toSet()
            val lease = coordination.acquire(record.operationId, owners) ?: return null
            var port: ItemOperationPort? = null
            var worker: ItemRollbackJournalWorker? = null
            var retention: ItemJournalRetention? = null
            try {
                port = bind(lease)
                check(lease.isCurrent(owners) && port.isExclusiveAndCurrent(owners)) { "Trusted exclusion is unavailable" }
                val original = checkNotNull(port.readOwners(owners))
                check(validImage(original, owners) && lease.isCurrent(owners) && port.isExclusiveAndCurrent(owners))
                worker = ItemRollbackJournalWorker(journal)
                retention = lease.retainUntilJournalReconciled(worker)
                return ItemOperationHost(lease, worker, port, retention, record, original, clock, requireDurableImages)
            } catch (failure: Exception) {
                worker?.close()
                lease.close()
                runCatching { port?.close() }
                // No journal request is submitted before start returns. Startup failure has no late outcome.
                if (retention?.isDrained == true) retention.releaseAfterReconciliation()
                throw failure
            }
        }
        private fun validImage(image: InventorySnapshot, owners: Set<ItemSlotOwner>) =
            image.slots.size <= 2048 && image.slots.keys.map { it.owner }.toSet() == owners &&
                image.slots.values.sumOf { (it.itemData?.size ?: 0).toLong() } <= 16L * 1024 * 1024
    }
}
