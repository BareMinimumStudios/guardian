package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import java.util.concurrent.CompletionStage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Trusted platform contract, deliberately not implemented by live Minecraft yet. */
interface ItemSavePort {
    /** All owners remain exclusively coordinated with unchanged identities and restored contents. */
    fun isExclusiveAndCurrent(owners: Set<ItemSlotOwner>): Boolean
    /**
     * Must persist the owner, finish its save/flush and read actual saved data afterwards.
     * Live snapshots and reads served from pending-write caches are not valid readback.
     * Null/failure means persistence cannot be established. This contract does not promise power-loss durability.
     */
    fun saveAndReadBack(owner: ItemSlotOwner,addresses: Set<ItemSlotAddress>): CompletionStage<InventorySnapshot?>
}

enum class ItemSaveCompletionState { CHECKING, COMPLETED, UNRESOLVED }

/**
 * Serialized coordinator protocol, with no inventory setters. Not exposed by commands.
 * A platform driver must keep exclusive ownership throughout and run journal I/O off the server thread.
 * Calling advance polls at most one save; it never waits on an incomplete future.
 */
class ItemSaveCompletion(private val record: ItemRollbackRecord,private val journal: ItemRollbackJournal,private val port: ItemSavePort,private val clock: () -> Long = System::nanoTime) {
    private val thread=Thread.currentThread()
    private val check=ItemRecoveryCheck(record)
    private val owners=check.owners.toSet()
    private val expected=linkedMapOf<ItemSlotAddress,ItemStackSnapshot>().apply { record.entries.forEach { entry -> entry.changes.forEach { put(it.address,it.before) } } }
    private val started=clock()
    private var index=0
    private var waiting: CompletableFuture<InventorySnapshot?>?=null
    var state=ItemSaveCompletionState.CHECKING;private set
    var reason: String?=null;private set
    init { require(record.phase==ItemRollbackPhase.APPLYING || record.phase==ItemRollbackPhase.RECOVERY_REQUIRED) }

    fun advance(): ItemSaveCompletionState {
        check(Thread.currentThread()===thread) { "Save completion must use one serialized driver thread" }
        if(state!=ItemSaveCompletionState.CHECKING) return state
        try {
            if(clock()-started>=TimeUnit.SECONDS.toNanos(10)) return refuse("Save/readback timed out")
            if(!port.isExclusiveAndCurrent(owners)) return refuse("Exclusive ownership, identity or restored contents changed")
            if(waiting==null) {
                if(!sameJournal(journal.itemRollback(record.operationId))) return refuse("Journal changed before save")
                val owner=check.owners[index]
                waiting=port.saveAndReadBack(owner,check.addresses(owner)).toCompletableFuture()
                return state
            }
            val future=waiting!!
            if(!future.isDone) return state
            val saved=future.join() ?: return refuse("Saved inventory is unavailable")
            val owner=check.owners[index];val wanted=check.addresses(owner)
            val bytes=wanted.sumOf { (saved.slots[it]?.itemData?.size ?: 0).toLong() }
            if(bytes>16L*1024*1024 || wanted.any { saved.slots[it]!=expected[it] }) return refuse("Saved slots do not match the restored state")
            waiting=null;index++
            if(index==check.owners.size) {
                if(!port.isExclusiveAndCurrent(owners) || !sameJournal(journal.itemRollback(record.operationId))) return refuse("Ownership or journal changed after save")
                if(!journal.transitionItemRollback(record.operationId,record.phase,ItemRollbackPhase.COMPLETED)) return refuse("Journal completion was not confirmed")
                state=ItemSaveCompletionState.COMPLETED
            }
        } catch(_: Exception) { return refuse("Save, readback or journal operation failed; completion is unresolved") }
        return state
    }
    /** Stops this driver only. In-flight saves may still finish; no claims or phases are cleared here. */
    fun stop(): ItemSaveCompletionState {
        check(Thread.currentThread()===thread)
        if(state==ItemSaveCompletionState.CHECKING) refuse("Save completion stopped")
        return state
    }
    private fun sameJournal(other: ItemRollbackRecord?)=other!=null && other.operationId==record.operationId && other.phase==record.phase && other.createdAt==record.createdAt && other.entries.size==record.entries.size && other.entries.zip(record.entries).all { (a,b) -> a.transactionId==b.transactionId && a.changes==b.changes }
    private fun refuse(message: String): ItemSaveCompletionState { waiting=null;reason=message;state=ItemSaveCompletionState.UNRESOLVED;return state }
}
