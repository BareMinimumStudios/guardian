package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import java.util.concurrent.CompletionStage

/** Only a trusted platform may supply exclusion. Closing releases resources, never mutates items. */
interface ItemOperationPort : ItemApplyPort, ItemSavePort, AutoCloseable

enum class ItemOperationState { APPLYING, SAVING, DRAINING, COMPLETED, RECOVERY_REQUIRED }

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
    private val clock: () -> Long
) : AutoCloseable {
    private val thread = Thread.currentThread()
    private val owners = lease.owners
    private val virtual = original.slots.toMutableMap()
    private var closedPort = false
    private var advancing = false
    private var confirmed = false
    var state = ItemOperationState.APPLYING; private set
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
    private val savePort = object : ItemSavePort {
        override fun isExclusiveAndCurrent(owners: Set<ItemSlotOwner>) =
            owners == this@ItemOperationHost.owners && current() && image()?.slots == virtual
        override fun saveAndReadBack(owner: ItemSlotOwner, addresses: Set<ItemSlotAddress>): CompletionStage<InventorySnapshot?> {
            check(isExclusiveAndCurrent(owners) && owner in owners)
            val expected = virtual.filterKeys { it.owner == owner }
            check(addresses.all { it in expected })
            // Pure immutable comparison may run on a worker; it makes no platform calls.
            return port.saveAndReadBack(owner, addresses).thenApply { saved ->
                saved?.takeIf { it.slots == expected }
            }
        }
    }
    private val apply = ItemApplyDriver(record, worker, applyPort, clock)
    private var save: AsyncItemSaveCompletion? = null
    val completionAttempt: CompletionStage<Boolean>? get() { checkThread(); return save?.completionAttempt }
    val isJournalDrained: Boolean get() { checkThread(); return retention.isDrained }

    fun advance(): ItemOperationState {
        checkThread()
        check(!advancing) { "Item operation polling is not reentrant" }
        if (state == ItemOperationState.COMPLETED || state == ItemOperationState.RECOVERY_REQUIRED) return state
        advancing = true
        try {
            when (state) {
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
                        AsyncItemSaveState.COMPLETED -> drain(true, null)
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
        drain(false, "Operation stopped; persistent outcome requires reconciliation")
    }

    private fun drain(success: Boolean, message: String?) {
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
        if (!retention.isDrained) return
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
                  clock: () -> Long = System::nanoTime): ItemOperationHost? {
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
                return ItemOperationHost(lease, worker, port, retention, record, original, clock)
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
