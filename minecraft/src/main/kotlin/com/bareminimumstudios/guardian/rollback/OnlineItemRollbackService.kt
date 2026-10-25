package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.logging.*
import com.bareminimumstudios.guardian.lookup.BlockHistoryService
import com.bareminimumstudios.guardian.permission.PermissionService
import com.bareminimumstudios.guardian.platform.minecraft.MinecraftItemOperations
import com.bareminimumstudios.guardian.storage.query.ContainerLookupQuery
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import java.util.UUID
import java.util.concurrent.TimeUnit

/** One bounded confirmed operation. Database callbacks never grant live mutation authority. */
internal class OnlineItemRollbackService(
    private val server: MinecraftServer,
    private val history: BlockHistoryService,
    private val pipeline: BufferedLogPipeline,
    private val permissions: PermissionService,
    private val operations: MinecraftItemOperations,
    private val otherBusy: () -> Boolean
) {
    private class Session(val source: CommandSourceStack, val query: ContainerLookupQuery, val max: Int) {
        val token = UUID.randomUUID()
        val id = UUID.randomUUID()
        val started = System.nanoTime()
        var deadline = started + TimeUnit.SECONDS.toNanos(10)
        var rows: List<ContainerTransactionSnapshot>? = null
        var watch: AuditOwnerObservation? = null
        var barrier: AuditWriteBarrier? = null
        var preparing = false
        var cancelled = false
        var host: ItemOperationHost? = null
        var reportedRecovery = false
    }
    private var active: Session? = null
    private var stopped = false
    fun isBusy() = active != null
    fun blocksObservation(): Boolean = active?.let { session ->
        session.host?.let { it.state!=ItemOperationState.RECOVERY_REQUIRED || !it.isJournalDrained } ?: true
    } ?: false
    fun confirmationSuggestions(source: CommandSourceStack): List<String> {
        val session=active ?: return emptyList()
        return if(permitted(source) && sameRequester(session.source,source) && session.rows!=null && !session.preparing &&
            !session.cancelled && System.nanoTime()<=session.deadline) listOf(session.token.toString()) else emptyList()
    }
    private fun currentSource(source: CommandSourceStack) = source.player?.createCommandSourceStack() ?: source
    private fun permitted(source: CommandSourceStack): Boolean = runCatching {
        val fresh = currentSource(source)
        (source.player == null || server.playerList.getPlayer(source.player!!.uuid) === source.player) &&
            permissions.has(fresh,"guardian.rollback.items",3) && permissions.has(fresh,"guardian.rollback",3)
    }.getOrDefault(false)
    private fun sameRequester(a: CommandSourceStack, b: CommandSourceStack) =
        if (a.player != null || b.player != null) a.player?.uuid == b.player?.uuid else (a as com.bareminimumstudios.guardian.mixin.ItemCommandSourceAccessor).`guardian$itemCommandSource`() ===
            (b as com.bareminimumstudios.guardian.mixin.ItemCommandSourceAccessor).`guardian$itemCommandSource`()

    fun request(source: CommandSourceStack, query: ContainerLookupQuery, max: Int): Boolean {
        check(server.isSameThread)
        if (stopped || !permitted(source)) return fail(source,"Requires guardian.rollback.items and guardian.rollback, or operator level 3.")
        if (active != null || otherBusy()) return fail(source,"Another recovery or maintenance operation is active.")
        val session = Session(source,query,max.coerceIn(1,50)); active = session
        source.sendSystemMessage(Component.literal("Guardian: selecting item transactions for confirmation; no items changed."))
        fence(session) {
            history.lookupContainers(query.copy(limit=session.max+1,offset=0,oldestFirst=false), { rows ->
                if (!valid(session)) return@lookupContainers
                if (rows.isEmpty() || rows.size > session.max || rows.sumOf { it.changes.size } > 2048) {
                    refuse(session,"No transactions or too many transactions match. Narrow the filters."); return@lookupContainers
                }
                val owners = rows.flatMap { it.changes.map { change -> change.address.owner } }.toSet()
                if (owners.size > 32 || owners.any { it !is ItemSlotOwner.BlockContainer && it !is ItemSlotOwner.PlayerInventory } ||
                    owners.filterIsInstance<ItemSlotOwner.BlockContainer>().any { !inside(it,query) }) {
                    refuse(session,"Every transfer endpoint must be a supported persistent inventory inside the region."); return@lookupContainers
                }
                session.rows = rows.toList()
                session.deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
                source.sendSystemMessage(Component.literal("Guardian: ${rows.size} item transactions selected. Close all open menus, then /guardian rollback-items confirm ${session.token} within 30 seconds. Eligibility and complete inventory contents are checked before writing."))
            }, { refuse(session,"Item history lookup failed.") })
        }
        return true
    }

    fun confirm(source: CommandSourceStack, token: String): Boolean {
        check(server.isSameThread)
        val session = active ?: return fail(source,"No item rollback awaits confirmation.")
        if (!sameRequester(session.source,source) || token != session.token.toString() || !valid(session) ||
            session.rows == null || session.preparing || session.host != null) return fail(source,"Invalid, expired or unavailable item rollback confirmation.")
        session.preparing = true
        session.deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        val rows = checkNotNull(session.rows)
        session.watch = runCatching { pipeline.observeOwners(rows.flatMap { it.changes.map { c -> c.address.owner } }.distinct()) }
            .getOrElse { refuse(session,"Inventory observation could not be established."); return false }
        fence(session) {
            history.prepareItemRollback(session.id,System.currentTimeMillis(),rows,{ record ->
                if (!valid(session) || session.watch?.changedOwners()?.isNotEmpty() != false) {
                    history.cancelPreparedItemRollback(session.id, { clear(session) }, { clear(session) })
                    fail(session.source,"Item rollback admission cancelled. No item setters ran; inspect recovery journal ${session.id} if claims remain.")
                    return@prepareItemRollback
                }
                try {
                    // The complete reverse chain and full owner images are validated by the protected host.
                    session.host = checkNotNull(operations.admitOwned(record) { !stopped && !session.cancelled && permitted(session.source) })
                    session.watch?.close(); session.watch = null
                    session.deadline = Long.MAX_VALUE
                    session.source.sendSystemMessage(Component.literal("Guardian: applying item rollback ${session.id}. Inventory actions are temporarily paused while saving."))
                } catch (error: Exception) {
                    history.cancelPreparedItemRollback(session.id,{ clear(session) },{ clear(session) })
                    fail(session.source,"Item rollback refused before apply: ${error.message?.take(180) ?: "unsupported inventory"}. Recovery journal: ${session.id}.")
                }
            }, { refuse(session,"Persistent admission refused: changed/newer history, claimed transactions, or unavailable storage.") })
        }
        return true
    }

    /** Reconciliation observes actual saved images. It never retries partial slot writes. */
    fun reconcile(source: CommandSourceStack): Boolean {
        val session = active ?: return fail(source,"No retained online operation is available in this process. Use recovery saved <UUID> after a restart.")
        if (!sameRequester(session.source,source) || !permitted(source)) return fail(source,"This operation belongs to another requester or permission was removed.")
        val host = session.host ?: return fail(source,"No item apply has started.")
        if (host.state != ItemOperationState.RECOVERY_REQUIRED || !host.isJournalDrained) return fail(source,"Operation must finish draining before reconciliation.")
        return try { operations.reconcileOwned(host); session.cancelled=false; session.reportedRecovery=false; true }
        catch (_: Exception) { fail(source,"Reconciliation cannot establish exclusive ownership; protection remains.") }
    }

    fun cancel(source: CommandSourceStack): Boolean {
        val session = active ?: return false
        if (!sameRequester(session.source,source) || !permitted(source)) return false
        session.cancelled=true
        session.host?.let { operations.cancel(it); return true }
        if (!session.preparing) clear(session)
        return true
    }

    fun tick() {
        val session = active ?: return
        val host = session.host
        if (host == null) { valid(session); return }
        if ((!permitted(session.source) || stopped || session.cancelled) &&
            host.state !in setOf(ItemOperationState.COMPLETED,ItemOperationState.RECOVERY_REQUIRED,ItemOperationState.DRAINING)) operations.cancel(host)
        when (host.state) {
            ItemOperationState.COMPLETED -> {
                if (permitted(session.source)) session.source.sendSystemMessage(Component.literal("Guardian item rollback completed and saved: ${session.rows!!.size} transactions. Operation ${session.id}."))
                clear(session)
            }
            ItemOperationState.RECOVERY_REQUIRED -> if (!session.reportedRecovery) {
                session.reportedRecovery=true
                if (permitted(session.source)) fail(session.source,"Operation ${session.id} needs recovery; inventories remain protected. Use /guardian rollback-items reconcile, or recovery saved ${session.id}. Partial writes are never replayed automatically.")
            }
            else -> Unit
        }
    }

    fun stop() { stopped=true; active?.let { it.cancelled=true;it.watch?.close();it.barrier?.cancel() } }
    private fun valid(session: Session): Boolean {
        if (active !== session || session.host != null) return active === session
        if (stopped || session.cancelled || !permitted(session.source) || System.nanoTime() > session.deadline) {
            if (!session.preparing) refuse(session,"Request cancelled, expired or permission removed.")
            else session.cancelled=true
            return false
        }
        return true
    }
    private fun fence(session: Session, next: () -> Unit) {
        session.barrier=pipeline.writeBarrier()
        session.barrier!!.result.whenComplete { _, error -> server.execute {
            if (!valid(session)) { if(session.host==null) clear(session); return@execute }
            if (error != null) refuse(session,"Accepted audit writes could not be confirmed.") else next()
        } }
    }
    private fun clear(session: Session) { if(active !== session)return;session.watch?.close();session.barrier?.cancel();active=null }
    private fun refuse(session: Session, message: String) { if(active !== session)return;fail(session.source,message+" No items changed.");clear(session) }
    private fun fail(source: CommandSourceStack, message: String): Boolean { source.sendFailure(Component.literal("Guardian item rollback: $message"));return false }
    private fun inside(owner: ItemSlotOwner.BlockContainer, query: ContainerLookupQuery): Boolean {
        if(owner.dimension!=query.dimension)return false
        query.bounds?.let { return it.contains(owner.position) }
        val center=query.position ?: return false
        val radius=query.radius ?: 0
        return kotlin.math.abs(owner.position.x.toLong()-center.x)<=radius && kotlin.math.abs(owner.position.y.toLong()-center.y)<=radius && kotlin.math.abs(owner.position.z.toLong()-center.z)<=radius
    }
}
