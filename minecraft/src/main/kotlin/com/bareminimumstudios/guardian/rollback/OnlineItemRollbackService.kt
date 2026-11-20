package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.checkpoint.*
import com.bareminimumstudios.guardian.platform.minecraft.MinecraftCheckpointCapture
import com.bareminimumstudios.guardian.platform.minecraft.GuardianMinecraftVersion
import com.bareminimumstudios.guardian.logging.OperationTimings
import com.bareminimumstudios.guardian.logging.OperationTimings.Stage
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
    private class Session(val source: CommandSourceStack, val query: ContainerLookupQuery, val max: Int,val purpose: ItemRecoveryPurpose) {
        val token = UUID.randomUUID()
        val id = UUID.randomUUID()
        val started = System.nanoTime()
        var deadline = started + TimeUnit.SECONDS.toNanos(10)
        var rows: List<ContainerTransactionSnapshot>? = null
        var restore: ItemRestoreSource? = null
        var checkpoint: CheckpointRestoreSource? = null
        var before: MinecraftCheckpointCapture.Observed? = null
        var checkpointLimits: InventoryCheckpointLimits? = null
        var watch: AuditOwnerObservation? = null
        var barrier: AuditWriteBarrier? = null
        var confirmedAt=0L
        var applyStartedAt=0L
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
    private fun permitted(source: CommandSourceStack,purpose: ItemRecoveryPurpose = active?.purpose ?: ItemRecoveryPurpose.TRANSFER_ROLLBACK): Boolean = runCatching {
        val fresh = currentSource(source)
        (source.player == null || server.playerList.getPlayer(source.player!!.uuid) === source.player) &&
            permissions.has(fresh,operationPermission(purpose),if(purpose==ItemRecoveryPurpose.CHECKPOINT_RESTORE)4 else 3) && permissions.has(fresh,basePermission(purpose),3)
    }.getOrDefault(false)
    private fun sameRequester(a: CommandSourceStack, b: CommandSourceStack) =
        if (a.player != null || b.player != null) a.player?.uuid == b.player?.uuid else (a as com.bareminimumstudios.guardian.mixin.ItemCommandSourceAccessor).`guardian$itemCommandSource`() ===
            (b as com.bareminimumstudios.guardian.mixin.ItemCommandSourceAccessor).`guardian$itemCommandSource`()

    fun requestItemRestore(source: CommandSourceStack, parent: UUID): Boolean {
        check(server.isSameThread)
        if(stopped || !permitted(source,ItemRecoveryPurpose.TRANSFER_REDO))
            return fail(source,"Requires guardian.restore and guardian.restore.items, or operator level 3.")
        if(active!=null || otherBusy()) return fail(source,"Another recovery or maintenance operation is active.")
        val session=Session(source,ContainerLookupQuery(),50,ItemRecoveryPurpose.TRANSFER_REDO)
        active=session
        source.sendSystemMessage(Component.literal("Guardian: selecting saved item rollback $parent for redo; no items changed."))
        fence(session) {
            history.itemRestoreSource(parent,{ restore ->
                if(!valid(session)) return@itemRestoreSource
                if(!restore.guard.clear) { refuse(session,"Newer history, changed blocks or reserved inventories prevent redo.");return@itemRestoreSource }
                session.restore=restore
                session.rows=emptyList()
                session.deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
                source.sendSystemMessage(Component.literal("Guardian: ${restore.images.record.entries.size} saved transactions selected. Close all menus, then /guardian restore-items confirm ${session.token} within 30 seconds. Every inventory must still match the saved rollback result."))
            },{ refuse(session,"Saved rollback cannot be redone: ${it.message?.take(180) ?: "unavailable receipt"}.") })
        }
        return true
    }

    fun requestCheckpointRestore(source:CommandSourceStack,parent:UUID,limits:InventoryCheckpointLimits):Boolean {
        check(server.isSameThread)
        if(stopped || !permitted(source,ItemRecoveryPurpose.CHECKPOINT_RESTORE))
            return fail(source,"Requires guardian.inventory and guardian.inventory.restore (operator level 4 by default).")
        if(active!=null || otherBusy())return fail(source,"Another recovery or maintenance operation is active.")
        val session=Session(source,ContainerLookupQuery(),1,ItemRecoveryPurpose.CHECKPOINT_RESTORE)
        session.checkpointLimits=limits;active=session
        fence(session) {
            history.checkpointRestoreSource(parent,{ saved ->
                if(!valid(session))return@checkpointRestoreSource
                if(saved.checkpoint.minecraftVersion!=GuardianMinecraftVersion.current) {
                    refuse(session,"Checkpoint belongs to a different Minecraft version.");return@checkpointRestoreSource
                }
                val target=server.playerList.getPlayer(saved.checkpoint.playerId)
                if(target==null) { refuse(session,"Checkpoint owner must be online.");return@checkpointRestoreSource }
                session.checkpoint=saved;session.rows=emptyList()
                session.deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
                source.sendSystemMessage(Component.literal("Guardian: replace ${saved.checkpoint.playerName}'s full persistent inventory with checkpoint $parent. This can recreate items moved elsewhere; outside inventories are not reversed. The current inventory will be saved separately for undo. Close menus, then /guardian inventory restore confirm ${session.token} within 30 seconds."))
            },{ refuse(session,"Checkpoint unavailable: ${it.message?.take(180) ?: "no saved source"}.") })
        }
        return true
    }
    fun confirmCheckpointRestore(source:CommandSourceStack,token:String):Boolean=confirmFor(source,token,ItemRecoveryPurpose.CHECKPOINT_RESTORE)

    fun confirmItemRestore(source: CommandSourceStack,token:String):Boolean = confirmFor(source,token,ItemRecoveryPurpose.TRANSFER_REDO)

    private fun operationCommand(purpose: ItemRecoveryPurpose): String = when(purpose) {
        ItemRecoveryPurpose.TRANSFER_ROLLBACK -> "rollback-items"
        ItemRecoveryPurpose.CREATIVE_DELETION -> "recover-items"
        ItemRecoveryPurpose.TRANSFER_REDO -> "restore-items"
        ItemRecoveryPurpose.CHECKPOINT_RESTORE -> "inventory restore"
    }
    private fun operationName(purpose: ItemRecoveryPurpose): String = when(purpose) {
        ItemRecoveryPurpose.TRANSFER_ROLLBACK -> "rollback"
        ItemRecoveryPurpose.CREATIVE_DELETION -> "recovery"
        ItemRecoveryPurpose.TRANSFER_REDO -> "redo"
        ItemRecoveryPurpose.CHECKPOINT_RESTORE -> "checkpoint restore"
    }
    private fun operationPermission(purpose: ItemRecoveryPurpose): String = when(purpose) {
        ItemRecoveryPurpose.TRANSFER_ROLLBACK -> "guardian.rollback.items"
        ItemRecoveryPurpose.CREATIVE_DELETION -> "guardian.recover.items"
        ItemRecoveryPurpose.TRANSFER_REDO -> "guardian.restore.items"
        ItemRecoveryPurpose.CHECKPOINT_RESTORE -> "guardian.inventory.restore"
    }
    private fun basePermission(purpose: ItemRecoveryPurpose) = when(purpose) {
        ItemRecoveryPurpose.TRANSFER_REDO -> "guardian.restore"
        ItemRecoveryPurpose.CHECKPOINT_RESTORE -> "guardian.inventory"
        else -> "guardian.rollback"
    }
    private fun transactionCount(session:Session) = if(session.checkpoint!=null)1 else session.restore?.images?.record?.entries?.size ?: checkNotNull(session.rows).size

    fun request(source: CommandSourceStack, query: ContainerLookupQuery, max: Int): Boolean = requestFor(source,query,max,ItemRecoveryPurpose.TRANSFER_ROLLBACK)
    fun requestCreativeRecovery(source: CommandSourceStack,query: ContainerLookupQuery,max:Int):Boolean = requestFor(source,query,max,ItemRecoveryPurpose.CREATIVE_DELETION)
    private fun requestFor(source: CommandSourceStack, query: ContainerLookupQuery, max: Int,purpose: ItemRecoveryPurpose): Boolean {
        check(server.isSameThread)
        if (stopped || !permitted(source,purpose)) return fail(source,"Requires ${if(purpose==ItemRecoveryPurpose.CREATIVE_DELETION) "guardian.recover.items" else "guardian.rollback.items"} and guardian.rollback, or operator level 3.")
        if (active != null || otherBusy()) return fail(source,"Another recovery or maintenance operation is active.")
        val session = Session(source,query,max.coerceIn(1,50),purpose); active = session
        source.sendSystemMessage(Component.literal("Guardian: selecting item transactions for confirmation; no items changed."))
        fence(session) {
            history.lookupContainers(query.copy(limit=session.max+1,offset=0,oldestFirst=false), { found ->
                val rows=if(purpose==ItemRecoveryPurpose.CREATIVE_DELETION) found.filter { it.action==ContainerAction.CREATIVE_SET } else found
                if (!valid(session)) return@lookupContainers
                if (rows.isEmpty() || found.size > session.max || rows.sumOf { it.changes.size } > 2048) {
                    refuse(session,"No transactions or too many transactions match. Narrow the filters."); return@lookupContainers
                }
                if(purpose==ItemRecoveryPurpose.CREATIVE_DELETION && (query.actorUuid==null || rows.any { !ItemRecoveryPolicy.transaction(purpose,it) })) {
                    refuse(session,"Recovery requires one online player and recorded creative item-to-empty deletions. Narrow the time window.");return@lookupContainers
                }
                val owners = rows.flatMap { it.changes.map { change -> change.address.owner } }.toSet()
                if (owners.size > 32 || owners.any { it !is ItemSlotOwner.BlockContainer && it !is ItemSlotOwner.PlayerInventory } ||
                    owners.filterIsInstance<ItemSlotOwner.BlockContainer>().any { !inside(it,query) }) {
                    refuse(session,"Every transfer endpoint must be a supported persistent inventory inside the region."); return@lookupContainers
                }
                session.rows = rows.toList()
                session.deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
                source.sendSystemMessage(Component.literal("Guardian: ${rows.size} item transactions selected. Close all open menus, then /guardian ${if(purpose==ItemRecoveryPurpose.CREATIVE_DELETION) "recover-items" else "rollback-items"} confirm ${session.token} within 30 seconds. Eligibility and complete inventory contents are checked before writing."))
            }, { refuse(session,"Item history lookup failed.") })
        }
        return true
    }

    fun confirm(source: CommandSourceStack, token: String): Boolean = confirmFor(source,token,ItemRecoveryPurpose.TRANSFER_ROLLBACK)
    fun confirmCreativeRecovery(source: CommandSourceStack,token:String):Boolean = confirmFor(source,token,ItemRecoveryPurpose.CREATIVE_DELETION)
    private fun confirmFor(source: CommandSourceStack, token: String,purpose:ItemRecoveryPurpose): Boolean {
        check(server.isSameThread)
        val session = active ?: return fail(source,"No item recovery or rollback awaits confirmation.")
        if (session.purpose!=purpose || !sameRequester(session.source,source) || token != session.token.toString() || !valid(session) ||
            session.rows == null || session.preparing || session.host != null) return fail(source,"Invalid, expired or unavailable confirmation for this command.")
        session.confirmedAt=System.nanoTime()
        session.preparing = true
        session.deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        val rows = checkNotNull(session.rows)
        session.watch = runCatching { pipeline.observeOwners(session.checkpoint?.let { listOf(ItemSlotOwner.PlayerInventory(it.checkpoint.playerId)) } ?: session.restore?.images?.expected?.slots?.keys?.map { it.owner }?.distinct() ?: rows.flatMap { it.changes.map { c -> c.address.owner } }.distinct()) }
            .getOrElse { refuse(session,"Inventory observation could not be established."); return false }
        fence(session) {
            if(session.checkpoint!=null) {
                try {
                    val player=server.playerList.getPlayer(checkNotNull(session.checkpoint).checkpoint.playerId) ?: error("Checkpoint owner is offline")
                    session.before=MinecraftCheckpointCapture.observe(server,player,InventoryCheckpointReason.BEFORE_RESTORE)
                    // Check shape/version/no-op before persisting claims and the complete undo image.
                    checkNotNull(session.checkpoint).record(session.id,checkNotNull(session.before).checkpoint.capturedAt,checkNotNull(session.before).checkpoint)
                } catch(error:Exception) { refuse(session,error.message?.take(180) ?: "Current inventory cannot be captured.");return@fence }
            }
            val prepareStarted=System.nanoTime()
            prepare(session,rows,{ record ->
                OperationTimings.record(Stage.ROLLBACK_PREPARE,System.nanoTime()-prepareStarted)
                if (!valid(session) || session.watch?.changedOwners()?.isNotEmpty() != false || session.before?.binding?.failureReason()!=null) {
                    history.cancelPreparedItemRollback(session.id, { clear(session) }, { clear(session) })
                    fail(session.source,"Item rollback admission cancelled. No item setters ran; inspect recovery journal ${session.id} if claims remain.")
                    return@prepare
                }
                session.applyStartedAt=System.nanoTime()
                try {
                    // The complete reverse chain and full owner images are validated by the protected host.
                    session.host = checkNotNull(operations.admitOwned(record,session.before?.checkpoint?.inventory ?: session.restore?.images?.expected) { !stopped && !session.cancelled && permitted(session.source) })
                    session.watch?.close(); session.watch = null
                    session.deadline = Long.MAX_VALUE
                    session.source.sendSystemMessage(Component.literal("Guardian: applying item ${operationName(session.purpose)} ${session.id}. Inventory actions are temporarily paused while saving."))
                } catch (error: Exception) {
                    history.cancelPreparedItemRollback(session.id,{ clear(session) },{ clear(session) })
                    fail(session.source,"Item rollback refused before apply: ${error.message?.take(180) ?: "unsupported inventory"}. Recovery journal: ${session.id}.")
                }
            }, { OperationTimings.record(Stage.ROLLBACK_PREPARE,System.nanoTime()-prepareStarted);refuse(session,if(session.purpose==ItemRecoveryPurpose.CHECKPOINT_RESTORE && (it is CheckpointRestoreRefusedException || it is InventoryCheckpointCapacityException))
                "Checkpoint admission refused: ${it.message?.filter { c -> !c.isISOControl() && c.code!=167 }?.take(180)}." else "Persistent admission refused: changed/newer history, claimed transactions, or unavailable storage.") })
        }
        return true
    }

    private fun prepare(session:Session,rows:List<ContainerTransactionSnapshot>,success:(ItemRollbackRecord)->Unit,failure:(Throwable)->Unit) {
        if(session.purpose==ItemRecoveryPurpose.CHECKPOINT_RESTORE)history.prepareCheckpointRestore(
            session.id,checkNotNull(session.before).checkpoint,checkNotNull(session.checkpoint).checkpoint.checkpointId,
            CheckpointRequester(session.source.player?.uuid,session.source.player?.gameProfile?.name ?: "Console"),checkNotNull(session.checkpointLimits),success,failure)
        else if(session.purpose==ItemRecoveryPurpose.TRANSFER_REDO) history.prepareItemRestore(session.id,System.currentTimeMillis(),checkNotNull(session.restore).images.record.operationId,success,failure)
        else if(session.purpose==ItemRecoveryPurpose.CREATIVE_DELETION) history.prepareCreativeRecovery(session.id,System.currentTimeMillis(),rows,success,failure)
        else history.prepareItemRollback(session.id,System.currentTimeMillis(),rows,success,failure)
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
                recordOutcome(session)
                if (permitted(session.source)) {
                    session.source.sendSystemMessage(Component.literal("Guardian item ${operationName(session.purpose)} completed and saved: ${transactionCount(session)} ${if(session.checkpoint!=null)"inventory" else "transactions"}. Operation ${session.id}."))
                    session.before?.checkpoint?.let { before ->
                        session.source.sendSystemMessage(Component.literal("To preview undo, use /guardian inventory restore preview ${before.checkpointId}. This saved the full inventory immediately before restoration."))
                    }
                }
                clear(session)
            }
            ItemOperationState.RECOVERY_REQUIRED -> if (!session.reportedRecovery) {
                recordOutcome(session)
                session.reportedRecovery=true
                if (permitted(session.source)) fail(session.source,"Operation ${session.id} needs recovery; inventories remain protected. Use /guardian ${operationCommand(session.purpose)} reconcile, or recovery saved ${session.id}. Partial writes are never replayed automatically.")
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
        val fenceStarted=System.nanoTime()
        session.barrier=pipeline.writeBarrier()
        session.barrier!!.result.whenComplete { _, error -> server.execute {
            OperationTimings.record(Stage.ROLLBACK_FENCE,System.nanoTime()-fenceStarted)
            if (!valid(session)) { if(session.host==null) clear(session); return@execute }
            if (error != null) refuse(session,"Accepted audit writes could not be confirmed.") else next()
        } }
    }
    private fun recordOutcome(session:Session) {
        val now=System.nanoTime()
        if(session.applyStartedAt!=0L) { OperationTimings.record(Stage.ROLLBACK_APPLY_SAVE,now-session.applyStartedAt);session.applyStartedAt=0L }
        if(session.confirmedAt!=0L) { OperationTimings.record(Stage.ROLLBACK_CONFIRM_TOTAL,now-session.confirmedAt);session.confirmedAt=0L }
    }
    private fun clear(session: Session) { if(active !== session)return;recordOutcome(session);session.watch?.close();session.barrier?.cancel();active=null }
    private fun refuse(session: Session, message: String) { if(active !== session)return;fail(session.source,message+" No items changed.");clear(session) }
    private fun fail(source: CommandSourceStack, message: String): Boolean { source.sendFailure(Component.literal("Guardian item operation: $message"));return false }
    private fun inside(owner: ItemSlotOwner.BlockContainer, query: ContainerLookupQuery): Boolean {
        if(owner.dimension!=query.dimension)return false
        query.bounds?.let { return it.contains(owner.position) }
        val center=query.position ?: return false
        val radius=query.radius ?: 0
        return kotlin.math.abs(owner.position.x.toLong()-center.x)<=radius && kotlin.math.abs(owner.position.y.toLong()-center.y)<=radius && kotlin.math.abs(owner.position.z.toLong()-center.z)<=radius
    }
}
