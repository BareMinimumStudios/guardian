package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.rollback.*
import net.minecraft.server.MinecraftServer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Internal lifecycle wiring. Commands cannot admit apply; exclusion remains a trusted contract. */
internal class MinecraftItemOperations(
    private val server: MinecraftServer,
    private val owners: ItemOwnerCoordination,
    private val journal: ItemRollbackJournal,
    private val busy: () -> Boolean
) {
    // Shared database ownership belongs to GuardianRuntime, never to this scope.
    private val scope = ItemOperationScope(journal) { }
    private class Entry(val host: ItemOperationHost, val lease: ItemOwnerCoordination.Lease) {
        var recovery: Recovery? = null
    }
    private class Recovery(val worker: ItemRollbackJournalWorker, val fence: CompletionStage<Void>, val quiescent: () -> Boolean) {
        var result: CompletableFuture<ItemRollbackRecord?>? = null
        var marking: CompletableFuture<Boolean>? = null
        val started = System.nanoTime()
        var closed = false
    }
    private val entries = arrayListOf<Entry>()
    private var controlling = false
    private var stopped = false
    private var drain: CompletionStage<Void>? = null

    fun admitPrepared(record: ItemRollbackRecord, exclusive: () -> Boolean): ItemOperationHost? = control {
        checkThread()
        check(!stopped && !busy()) { "Item operation admission is unavailable" }
        entries.removeAll { it.host.state == ItemOperationState.COMPLETED && it.recovery == null }
        check(entries.size < 32)
        var lease: ItemOwnerCoordination.Lease? = null
        val host = scope.startProtected(record, owners, { acquired ->
            lease = acquired
            check(exclusive()) { "Trusted physical exclusion is required" }
            val session = MinecraftInventoryCoordination.bindInventories(server, acquired)
            MinecraftBoundOperationPort(server, acquired, session, exclusive)
        }) ?: return@control null
        entries.add(Entry(host, checkNotNull(lease)))
        host
    }

    fun cancel(host: ItemOperationHost) = control {
        checkThread();check(!stopped)
        val entry = entries.single { it.host === host }
        entry.recovery?.let { it.closed=true;it.worker.close() }
        scope.cancel(host)
    }

    /** The caller must establish physical quiescence; this method never infers it from a lock. */
    fun requestRecovery(host: ItemOperationHost, quiescent: () -> Boolean) = control {
        checkThread();check(!stopped && !busy())
        val entry = entries.single { it.host === host }
        check(entry.recovery == null && host.state == ItemOperationState.RECOVERY_REQUIRED && host.isJournalDrained)
        check(quiescent()) { "Trusted physical quiescence is required" }
        val fence = MinecraftInventoryCoordination.retainedDiskFence(server, entry.lease)
        entry.recovery = Recovery(ItemRollbackJournalWorker(journal), fence, quiescent)
    }

    fun tick() = control {
        checkThread();if(stopped)return@control
        scope.advance()
        for(entry in entries.toList()) {
            if(stopped)break
            val recovery=entry.recovery ?: continue
            try { advanceRecovery(entry,recovery) }
            catch(_: Exception) { recovery.worker.close();recovery.closed=true }
            if(recovery.closed && recovery.worker.drained.toCompletableFuture().isDone)entry.recovery=null
        }
        entries.removeAll { it.host.state == ItemOperationState.COMPLETED && it.recovery == null }
    }

    private fun advanceRecovery(entry: Entry, recovery: Recovery) {
        if(recovery.closed)return
        check(System.nanoTime()-recovery.started < java.util.concurrent.TimeUnit.SECONDS.toNanos(10))
        check(recovery.quiescent() && entry.lease.isRetained(entry.lease.owners))
        val fence=recovery.fence.toCompletableFuture()
        if(!fence.isDone)return
        fence.join()
        if(recovery.result==null) {
            recovery.result=recovery.worker.read(entry.lease.operationId).toCompletableFuture();return
        }
        val result=checkNotNull(recovery.result)
        if(!result.isDone)return
        val record=checkNotNull(result.join())
        check(entry.host.matchesRecord(record)) { "Persistent recovery plan changed" }
        if(record.phase==ItemRollbackPhase.APPLYING) {
            if(recovery.marking==null) {recovery.marking=recovery.worker.markRecovery(record).toCompletableFuture();return}
            if(!recovery.marking!!.isDone)return
            check(recovery.marking!!.join())
            recovery.marking=null
            recovery.result=recovery.worker.read(entry.lease.operationId).toCompletableFuture();return
        }
        check(record.phase==ItemRollbackPhase.COMPLETED || record.phase==ItemRollbackPhase.RECOVERY_REQUIRED)
        recovery.worker.close()
        // The read/transition result is not proof that its worker has physically drained.
        if(!recovery.worker.drained.toCompletableFuture().isDone)return
        val session=MinecraftInventoryCoordination.bindReadOnlyInventories(server,entry.lease)
        val port=MinecraftBoundReconciliationPort(server,entry.lease,session,recovery.quiescent)
        try { scope.reconcile(entry.host,record,port,recovery.fence) }
        catch(error: Exception) {port.close();throw error}
        recovery.closed=true
    }

    /** Stop admission first; completion can settle after the last server tick without platform calls. */
    fun stopAndDrain(): CompletionStage<Void> {
        checkThread()
        check(!controlling) { "Shutdown cannot run from an operation callback" }
        drain?.let {return it}
        stopped=true
        val queries=entries.mapNotNull { it.recovery?.worker }.also { workers -> workers.forEach { it.close() } }
        val hostDrain=scope.stopAndDrain()
        return CompletableFuture.allOf(hostDrain.toCompletableFuture(),
            *queries.map { it.drained.toCompletableFuture() }.toTypedArray()).minimalCompletionStage().also {drain=it}
    }

    fun ownsAllRetentions(): Boolean {
        checkThread()
        return !owners.hasUnmanagedJournalRetention(entries.map { it.lease }.toSet())
    }
    private inline fun <T> control(action: () -> T): T {
        checkThread()
        check(!controlling) { "Item operation controls are not reentrant" }
        controlling=true
        try {return action()} finally {controlling=false}
    }
    private fun checkThread()=check(server.isSameThread)
}
