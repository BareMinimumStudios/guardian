package com.bareminimumstudios.guardian.purge

import com.bareminimumstudios.guardian.lookup.BlockHistoryService
import com.bareminimumstudios.guardian.permission.PermissionService
import com.bareminimumstudios.guardian.storage.purge.*
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Main-thread command lifecycle. Database work is serialized on the history executor. */
class AuditPurgeService(private val history: BlockHistoryService, private val permissions: PermissionService, private val otherBusy: () -> Boolean) {
    private data class Work(val source: CommandSourceStack, val owner: String, val applying: Boolean, val permit: AtomicBoolean = AtomicBoolean(true))
    private val tickets=PurgeTickets()
    private var ticketSource: CommandSourceStack?=null
    private var active: Work?=null
    private var stopped=false
    fun isBusy()=active!=null
    private fun owner(source: CommandSourceStack)=source.player?.uuid?.toString() ?: "console"
    private fun allowed(source: CommandSourceStack): Boolean {
        if(stopped)return false
        val player=source.player
        if(player!=null && source.server.playerList.getPlayer(player.uuid)!==player)return false
        return runCatching { permissions.has(player?.createCommandSourceStack() ?: source,"guardian.purge",4) }.getOrDefault(false)
    }
    private fun message(source: CommandSourceStack,text: String)=source.sendSystemMessage(Component.literal(text))
    private fun admit(source: CommandSourceStack): Boolean {
        if(!allowed(source))return false
        if(isBusy()||otherBusy()){source.sendFailure(Component.literal("Guardian purge is unavailable while purge, rollback, restore or item checks are busy."));return false}
        return true
    }
    fun preview(source: CommandSourceStack,ageMillis: Long): Boolean {
        if(!admit(source))return false
        if(ageMillis<86_400_000L){source.sendFailure(Component.literal("Guardian purge requires retaining at least one day: use t:1d or older."));return false}
        val cutoff=(System.currentTimeMillis()-ageMillis).coerceAtLeast(0)
        tickets.clear();ticketSource=null
        val work=Work(source,owner(source),false);active=work
        message(source,"Guardian: counting old history for purge preview; nothing will be deleted.")
        history.previewPurge(cutoff,{ plan ->
            if(active!==work)return@previewPurge
            active=null
            if(!work.permit.get()||!allowed(source))return@previewPurge
            message(source,"Guardian purge preview: global across all users/worlds; records strictly before ${java.time.Instant.ofEpochMilli(cutoff)} UTC. Eligible: ${plan.eligible.blocks} block, ${plan.eligible.containers} item, ${plan.eligible.activity} activity, ${plan.eligible.entities} entity/frame, ${plan.eligible.fluids} fluid. Protected: ${plan.protected.total}. This batch: ${plan.candidates.size}/1000.")
            if(plan.candidates.isEmpty()){message(source,"Guardian: no eligible old history to purge.");return@previewPurge}
            val ticket=tickets.offer(work.owner,plan);ticketSource=source
            message(source,"Permanent deletion of this selected block/item/activity/entity/fluid batch requires /guardian purge confirm ${ticket.token} within 60 seconds. Back up first. Only this fixed batch is selected; new inserts are excluded. Purged history cannot be rolled back or restored.")
        },{ error ->
            if(active!==work)return@previewPurge
            active=null
            if(allowed(source)&&work.permit.get())source.sendFailure(Component.literal("Guardian purge preview failed; no history changed. See server log."))
        })
        return true
    }
    fun confirm(source: CommandSourceStack,raw: String): Boolean {
        if(!admit(source))return false
        val token=runCatching { UUID.fromString(raw).also { require(it.toString().equals(raw,true)) } }.getOrNull()
        val plan=token?.let { tickets.take(owner(source),it) }
        if(plan==null){source.sendFailure(Component.literal("Guardian purge confirmation is invalid, expired or belongs to another requester. Run a new preview."));return false}
        ticketSource=null
        val work=Work(source,owner(source),true);active=work
        message(source,"Guardian: purging the confirmed batch of at most ${plan.candidates.size} old history records.")
        history.applyPurge(plan,{work.permit.get()},{ result ->
            if(active!==work)return@applyPurge
            active=null
            if(allowed(source))message(source,"Guardian purge complete: ${result.deleted.blocks} block, ${result.deleted.containers} item, ${result.deleted.activity} activity, ${result.deleted.entities} entity/frame, ${result.deleted.fluids} fluid deleted; ${result.skipped} selected records retained or already absent. Newer history and recovery records were preserved. Deleted history cannot be restored.")
        },{ error ->
            if(active!==work)return@applyPurge
            active=null
            if(allowed(source))source.sendFailure(Component.literal("Guardian purge stopped without confirmed success. Check history and server log before retrying; a committed batch cannot be undone."))
        })
        return true
    }
    fun suggestions(source: CommandSourceStack): List<String> = if(allowed(source))listOfNotNull(tickets.peek(owner(source))?.token?.toString())else emptyList()
    fun cancel(source: CommandSourceStack): Boolean {
        if(!allowed(source))return false
        val work=active?.takeIf { it.owner==owner(source) }
        val pending=tickets.cancel(owner(source));if(pending)ticketSource=null
        if(work!=null)work.permit.set(false)
        message(source,when { work?.applying==true -> "Guardian purge cancellation requested. An already committed batch cannot be undone; wait for its outcome."
            work!=null -> "Guardian purge preview cancelled; no history changed."
            pending -> "Guardian purge confirmation cancelled; no history changed."
            else -> "Guardian: no purge preview or confirmation for this requester." })
        return true
    }
    fun tick(){
        active?.let { if(!allowed(it.source))it.permit.set(false) }
        ticketSource?.let { if(!allowed(it)){tickets.cancel(owner(it));ticketSource=null} }
    }
    fun stop(){stopped=true;active?.permit?.set(false);tickets.clear();ticketSource=null}
}
