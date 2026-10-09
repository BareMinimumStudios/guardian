package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.rollback.*
import net.minecraft.server.MinecraftServer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.UUID

internal enum class RestartAdmissionState { LOADING, OWNED, COMPLETED, RECOVERY_REQUIRED, REFUSED }

internal class RestartRecoveryAdmission(val operationId: UUID,internal val quiescent: () -> Boolean) {
    var state=RestartAdmissionState.LOADING; internal set
    var reason: String?=null; internal set
    internal var host: ItemRestartRecoveryHost?=null
    internal var query: ItemRollbackJournalWorker?=null
    internal var result: CompletableFuture<ItemRollbackImageRecord?>?=null
    internal var started=System.nanoTime()
}

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
    private val restarts = arrayListOf<RestartRecoveryAdmission>()
    private var controlling = false
    private var stopped = false
    private var drain: CompletionStage<Void>? = null

    fun admitPrepared(record: ItemRollbackRecord, exclusive: () -> Boolean): ItemOperationHost? = control {
        checkThread()
        check(!stopped && !busy()) { "Item operation admission is unavailable" }
        entries.removeAll { it.host.state == ItemOperationState.COMPLETED && it.recovery == null }
        check(entries.size + restarts.size < 32)
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

    /** Explicit fresh-process handoff only. Startup and commands never automatically replay items. */
    fun requestRestartRecovery(operationId: UUID,quiescent: () -> Boolean): RestartRecoveryAdmission = control {
        check(!stopped && !busy())
        check(entries.size + restarts.size < 32)
        check(entries.none {it.lease.operationId==operationId} && restarts.none {it.operationId==operationId})
        check(quiescent()) { "Trusted fresh-process physical quiescence is required" }
        val admission=RestartRecoveryAdmission(operationId,quiescent)
        val worker=ItemRollbackJournalWorker(journal)
        admission.query=worker
        admission.result=worker.readImages(operationId).toCompletableFuture()
        restarts.add(admission)
        admission
    }

    fun retryRestartRecovery(admission: RestartRecoveryAdmission) = control {
        check(!stopped && !busy() && admission in restarts)
        check(admission.state==RestartAdmissionState.RECOVERY_REQUIRED && admission.host?.isJournalDrained==true && admission.query==null)
        check(admission.quiescent())
        admission.started=System.nanoTime();admission.reason=null
        val worker=ItemRollbackJournalWorker(journal)
        admission.query=worker;admission.result=worker.readImages(admission.operationId).toCompletableFuture()
        admission.state=RestartAdmissionState.LOADING
    }

    private fun restartBinding(lease: ItemOwnerCoordination.Lease,quiescent: () -> Boolean): ItemRestartRecoveryBinding {
        check(quiescent())
        val fence=MinecraftInventoryCoordination.retainedDiskFence(server,lease)
        val session=MinecraftInventoryCoordination.bindReadOnlyInventories(server,lease)
        return ItemRestartRecoveryBinding(MinecraftBoundReconciliationPort(server,lease,session,quiescent),fence)
    }

    private fun advanceRestart(admission: RestartRecoveryAdmission) {
        val query=admission.query
        if(query!=null) {
            if(System.nanoTime()-admission.started > java.util.concurrent.TimeUnit.SECONDS.toNanos(10)) {
                admission.state=if(admission.host==null)RestartAdmissionState.REFUSED else RestartAdmissionState.RECOVERY_REQUIRED
                admission.reason="Restart receipt query timed out";query.close()
            }
            if(admission.state==RestartAdmissionState.LOADING) {
                val result=checkNotNull(admission.result)
                if(!result.isDone)return
                query.close()
                if(!query.drained.toCompletableFuture().isDone)return
                val receipt=checkNotNull(result.join()) { "Complete persisted protection is unavailable" }
                check(receipt.images.record.operationId==admission.operationId)
                check(admission.quiescent())
                if(admission.host==null) {
                    admission.host=checkNotNull(ItemRestartRecoveryHost.start(receipt,owners,journal,
                        {restartBinding(it,admission.quiescent)})) { "Recovery owners are unavailable" }
                } else admission.host!!.retry(receipt.images.record) {restartBinding(it,admission.quiescent)}
                admission.state=RestartAdmissionState.OWNED
            }
            if(query.isStopped && query.drained.toCompletableFuture().isDone) {
                admission.query=null;admission.result=null
            }
        }
        if(admission.state==RestartAdmissionState.OWNED) {
            val host=checkNotNull(admission.host)
            when(host.advance()) {
                ItemRestartRecoveryState.COMPLETED -> admission.state=RestartAdmissionState.COMPLETED
                ItemRestartRecoveryState.RECOVERY_REQUIRED -> {admission.state=RestartAdmissionState.RECOVERY_REQUIRED;admission.reason=host.reason}
                else -> Unit
            }
        }
    }

    fun tick() = control {
        checkThread();if(stopped)return@control
        scope.advance()
        for(admission in restarts.toList()) {
            try {advanceRestart(admission)}
            catch(_: Exception) {
                admission.query?.close()
                admission.state=if(admission.host==null)RestartAdmissionState.REFUSED else RestartAdmissionState.RECOVERY_REQUIRED
                admission.reason="Restart recovery could not be admitted; persistent claims remain intact"
                if(admission.query?.drained?.toCompletableFuture()?.isDone==true) {admission.query=null;admission.result=null}
            }
        }
        restarts.removeAll {it.query==null && it.state in setOf(RestartAdmissionState.COMPLETED,RestartAdmissionState.REFUSED)}
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
        val restartQueries=restarts.mapNotNull {it.query}.also {it.forEach {worker -> worker.close()}}
        val restartHosts=restarts.mapNotNull {it.host}.also {it.forEach {host -> host.close()}}
        val hostDrain=scope.stopAndDrain()
        return CompletableFuture.allOf(hostDrain.toCompletableFuture(),
            *(queries.map {it.drained.toCompletableFuture()} + restartQueries.map {it.drained.toCompletableFuture()} +
                restartHosts.map {it.journalDrain.toCompletableFuture()}).toTypedArray()).minimalCompletionStage().also {drain=it}
    }

    fun ownsAllRetentions(): Boolean {
        checkThread()
        return !owners.hasUnmanagedJournalRetention((entries.map {it.lease} + restarts.mapNotNull {it.host?.lease}).toSet())
    }
    private inline fun <T> control(action: () -> T): T {
        checkThread()
        check(!controlling) { "Item operation controls are not reentrant" }
        controlling=true
        try {return action()} finally {controlling=false}
    }
    private fun checkThread()=check(server.isSameThread)
}
