package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

interface ItemReconciliationJournalPort {
    fun read(operationId: UUID): CompletionStage<ItemRollbackRecord?>
    fun readImages(operationId: UUID): CompletionStage<ItemRollbackImageRecord?>
    fun acknowledge(record: ItemRollbackRecord, images: ItemRollbackImages, saved: InventorySnapshot): CompletionStage<Boolean>
}

/**
 * Trusted platform contract: stable full owner identity, mutations excluded, and every prior
 * journal request and disk write actually drained. A lease or observational read is not enough.
 * Reads are complete immutable owner images from actual saved data; never save or mutate items.
 */
interface ItemReconciliationPort {
    fun isExclusiveAndQuiescent(owners: Set<ItemSlotOwner>): Boolean
    fun readLiveOwners(owners: Set<ItemSlotOwner>): InventorySnapshot?
    fun readSavedOwner(owner: ItemSlotOwner): CompletionStage<InventorySnapshot?>
}

enum class ItemReconciliationState { CHECKING_JOURNAL, READING_IMAGES, READING_SAVED, RECHECKING_JOURNAL, ACKNOWLEDGING, CONFIRMING, RESOLVED, ALREADY_RESOLVED, UNRESOLVED }

/** Internal read-only inventory protocol. Its durable decision never authorizes replay or undo. */
class ItemReconciliationDriver(
    private val record: ItemRollbackRecord,
    private val journal: ItemReconciliationJournalPort,
    private val port: ItemReconciliationPort,
    private val clock: () -> Long = System::nanoTime
) {
    private val thread=Thread.currentThread()
    private val started=clock()
    private val owners=ItemRecoveryCheck(record).owners.toSet()
    private var advancing=false
    private var read: CompletableFuture<ItemRollbackRecord?>?=null
    private var receipt: CompletableFuture<ItemRollbackImageRecord?>?=null
    private var images: ItemRollbackImages?=null
    private val saved=linkedMapOf<ItemSlotAddress,ItemStackSnapshot>()
    private val remaining=ArrayDeque(owners)
    private var savingOwner: ItemSlotOwner?=null
    private var readingSaved: CompletableFuture<InventorySnapshot?>?=null
    private var ack: CompletionStage<Boolean>?=null
    private var ackResult: CompletableFuture<Boolean>?=null
    var state=ItemReconciliationState.CHECKING_JOURNAL; private set
    var reason: String?=null; private set
    val acknowledgmentAttempt: CompletionStage<Boolean>? get() {checkThread();return ack?.thenApply { it }}
    init {require(record.phase==ItemRollbackPhase.COMPLETED || record.phase==ItemRollbackPhase.RECOVERY_REQUIRED)}

    fun advance(): ItemReconciliationState {
        checkThread();check(!advancing) { "Reconciliation polling is not reentrant" }
        if(terminal())return state
        if(clock()-started>TimeUnit.SECONDS.toNanos(10))return refuse("Reconciliation timed out")
        advancing=true
        try {
            when(state) {
                ItemReconciliationState.CHECKING_JOURNAL -> {
                    if(read==null) {
                        read=journal.read(record.operationId).toCompletableFuture()
                        if(state!=ItemReconciliationState.CHECKING_JOURNAL)return state
                    }
                    if(read!!.isDone) {
                        if(!sameSaveRecord(record,read!!.join()))return refuse("Journal changed before reconciliation")
                        receipt=journal.readImages(record.operationId).toCompletableFuture()
                        if(state!=ItemReconciliationState.CHECKING_JOURNAL)return state
                        state=ItemReconciliationState.READING_IMAGES
                    }
                }
                ItemReconciliationState.READING_IMAGES -> if(receipt!!.isDone) {
                    val value=receipt!!.join() ?: return refuse("Complete persistent owner images are unavailable")
                    if(!sameSaveRecord(record,value.images.record))return refuse("Receipt differs from the journal")
                    images=value.images
                    if(value.acknowledged) {
                        if(record.phase!=ItemRollbackPhase.COMPLETED)return refuse("Acknowledged receipt has a nonterminal journal")
                        state=ItemReconciliationState.ALREADY_RESOLVED
                    } else {
                        if(!current())return refuse("Trusted quiescence, identity or complete live contents are unavailable")
                        state=ItemReconciliationState.READING_SAVED
                    }
                }
                ItemReconciliationState.READING_SAVED -> {
                    if(!current())return refuse("Owners changed during saved-state reads")
                    if(readingSaved!=null) {
                        if(!readingSaved!!.isDone)return state
                        val owner=checkNotNull(savingOwner)
                        val value=readingSaved!!.join() ?: return refuse("Saved owner image is unavailable")
                        val wanted=checkNotNull(images).expected.slots.filterKeys { it.owner==owner }
                        if(value.slots!=wanted)return refuse("Complete saved contents differ from the expected image")
                        saved.putAll(value.slots);readingSaved=null;savingOwner=null
                    }
                    if(remaining.isEmpty()) {
                        read=journal.read(record.operationId).toCompletableFuture()
                        if(state!=ItemReconciliationState.READING_SAVED)return state
                        state=ItemReconciliationState.RECHECKING_JOURNAL
                    } else {
                        savingOwner=remaining.removeFirst()
                        readingSaved=port.readSavedOwner(checkNotNull(savingOwner)).thenApply { it }.toCompletableFuture()
                        if(state!=ItemReconciliationState.READING_SAVED)return state
                        if(!current())return refuse("Owners changed while a saved read started")
                    }
                }
                ItemReconciliationState.RECHECKING_JOURNAL -> if(read!!.isDone) {
                    if(!sameSaveRecord(record,read!!.join()))return refuse("Journal changed before acknowledgment")
                    if(!current() || saved!=checkNotNull(images).expected.slots)return refuse("Final complete image or quiescence check failed")
                    if(state!=ItemReconciliationState.RECHECKING_JOURNAL)return state
                    // This is the decision point. The trusted port has excluded mutations and drained
                    // old I/O; a late database acknowledgment records this verified outcome.
                    ack=journal.acknowledge(record,checkNotNull(images),InventorySnapshot(saved))
                    ackResult=ack!!.toCompletableFuture()
                    if(state!=ItemReconciliationState.RECHECKING_JOURNAL)return refuse("Stopped while acknowledgment started")
                    state=ItemReconciliationState.ACKNOWLEDGING
                }
                ItemReconciliationState.ACKNOWLEDGING -> if(ackResult!!.isDone) {
                    if(!ackResult!!.join())return refuse("Persistent acknowledgment was refused")
                    receipt=journal.readImages(record.operationId).toCompletableFuture()
                    if(state!=ItemReconciliationState.ACKNOWLEDGING)return state
                    state=ItemReconciliationState.CONFIRMING
                }
                ItemReconciliationState.CONFIRMING -> if(receipt!!.isDone) {
                    val value=receipt!!.join() ?: return refuse("Durable acknowledgment receipt is unavailable")
                    if(!value.acknowledged || value.images.record.phase!=ItemRollbackPhase.COMPLETED ||
                        !sameRollbackImages(checkNotNull(images),value.images))return refuse("Durable decision could not be confirmed")
                    state=ItemReconciliationState.RESOLVED
                }
                else -> Unit
            }
        } catch (_: Exception) {return refuse("Reconciliation failed")}
        finally {advancing=false}
        return state
    }
    /** Does not cancel started reads/acknowledgments or close the caller-owned worker/backend. */
    fun stop() {checkThread();if(!terminal())refuse("Reconciliation stopped")}
    private fun current(): Boolean {
        if(terminal() || !port.isExclusiveAndQuiescent(owners) || terminal())return false
        val live=port.readLiveOwners(owners)
        return !terminal() && port.isExclusiveAndQuiescent(owners) && !terminal() && live?.slots==images?.expected?.slots
    }
    private fun terminal()=state in setOf(ItemReconciliationState.RESOLVED,ItemReconciliationState.ALREADY_RESOLVED,ItemReconciliationState.UNRESOLVED)
    private fun refuse(message: String): ItemReconciliationState {
        reason=if(ack==null)message else "$message; acknowledgment was submitted and its durable outcome must be reconciled"
        state=ItemReconciliationState.UNRESOLVED;return state
    }
    private fun checkThread()=check(Thread.currentThread()===thread) { "Reconciliation controls must use their owning thread" }
}
