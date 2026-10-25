package com.bareminimumstudios.guardian.export

import com.bareminimumstudios.guardian.lookup.BlockHistoryService
import com.bareminimumstudios.guardian.permission.PermissionService
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Main-thread authority; query and file work share the draining history executor. */
class HistoryExportService(private val history:BlockHistoryService,private val permissions:PermissionService,private val root:Path) {
    private data class Work(val source:CommandSourceStack,val kind:ExportKind,val permit:AtomicBoolean=AtomicBoolean(true))
    private var active:Work?=null
    private var stopped=false
    private fun allowed(source:CommandSourceStack,kind:ExportKind):Boolean {
        if(stopped)return false
        val player=source.player
        if(player!=null && source.server.playerList.getPlayer(player.uuid)!==player)return false
        val current=player?.createCommandSourceStack() ?: source
        return runCatching { permissions.has(current,"guardian.export",4) && permissions.has(current,kind.permission,kind.fallback) }.getOrDefault(false)
    }
    fun request(source:CommandSourceStack,query:ExportQuery,filters:String,scope:Map<String,Any?>):Boolean {
        if(!allowed(source,query.kind))return false
        if(active!=null){source.sendFailure(Component.literal("Guardian: another export is running. Wait or cancel your own export."));return false}
        require(query.limit in 1..500)
        val work=Work(source,query.kind);active=work
        val requestedAt=System.currentTimeMillis();val requester=source.player?.uuid?.toString() ?: "console"
        source.sendSystemMessage(Component.literal("Guardian: exporting up to ${query.limit} ${query.kind.name.lowercase()} records to a server-side JSONL file."))
        fun failed(error:Throwable){
            if(active!==work)return
            active=null
            if(allowed(source,query.kind))source.sendFailure(Component.literal("Guardian export failed or was cancelled; no completed file was reported. ${error.message ?: "See server log"}"))
        }
        history.exportRows(query,{work.permit.get()},{ rows->
            if(active!==work)return@exportRows
            if(!work.permit.get() || !allowed(source,query.kind)){active=null;return@exportRows}
            history.exportFile(root,query.kind,query.limit,filters,requester,requestedAt,scope,rows,{work.permit.get()},{ result->
                if(active!==work)return@exportFile
                active=null
                if(allowed(source,query.kind))source.sendSystemMessage(Component.literal("Guardian export complete: ${result.count} records in guardian/exports/${result.filename}. ${if(result.mayHaveMore) "Limit reached; more records may match. " else ""}Completed exports contain sensitive stored data and remain on disk until a server administrator removes them."))
            },::failed)
        },::failed)
        return true
    }
    fun cancel(source:CommandSourceStack):Boolean {
        val work=active
        if(work==null){source.sendSystemMessage(Component.literal("Guardian: no export is running."));return true}
        if(!allowed(source,work.kind) || (source.player?.uuid!=work.source.player?.uuid))return false
        work.permit.set(false)
        source.sendSystemMessage(Component.literal("Guardian export cancellation requested. An already completed file remains on disk."));return true
    }
    fun tick(){active?.let { if(!allowed(it.source,it.kind))it.permit.set(false) }}
    fun stop(){stopped=true;active?.permit?.set(false)}
}
