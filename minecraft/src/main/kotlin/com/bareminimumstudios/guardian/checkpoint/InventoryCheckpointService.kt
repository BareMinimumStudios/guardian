package com.bareminimumstudios.guardian.checkpoint

import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import com.bareminimumstudios.guardian.command.CheckpointCommandFilters
import com.bareminimumstudios.guardian.lookup.BlockHistoryService
import com.bareminimumstudios.guardian.permission.PermissionService
import com.bareminimumstudios.guardian.platform.minecraft.*
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import java.util.UUID

/** Main-thread capture is bounded to one player/tick; at most four immutable images await persistence. */
class InventoryCheckpointService(
    private val server:MinecraftServer,private val history:BlockHistoryService,private val permissions:PermissionService,
    val automatic:Boolean,val intervalSeconds:Int,val limits:InventoryCheckpointLimits,
    private val captureAllowed:()->Boolean,private val otherBusy:()->Boolean
) {
    private val scan=ArrayDeque<UUID>()
    private val pending=mutableSetOf<UUID>()
    private var nextScan:Long?=null
    private var nanoTime:()->Long=System::nanoTime
    private var lastSkipReason="none"
    private var stopped=false
    private var reading=false
    private var captured=0L;private var unchanged=0L;private var failures=0L;private var skipped=0L
    private var suggesting=false
    private var lastSaveNanos=0L;private var maxSaveNanos=0L
    private var lastNanos=0L;private var maxNanos=0L;private var lastWarning=0L
    private val logger=LoggerFactory.getLogger("Guardian/Checkpoints")
    fun status()="Inventory checkpoints: automatic=$automatic pending=${pending.size} captured=$captured unchanged=$unchanged failures=$failures skipped=$skipped captureLastMs=${lastNanos/1_000_000} captureMaxMs=${maxNanos/1_000_000} saveLastMs=${lastSaveNanos/1_000_000} saveMaxMs=${maxSaveNanos/1_000_000} lastSkip=$lastSkipReason"

    fun permitted(source:CommandSourceStack,restore:Boolean=false):Boolean=runCatching {
        !stopped && (source.player==null || server.playerList.getPlayer(source.player!!.uuid)===source.player) &&
            permissions.has(source.player?.createCommandSourceStack() ?: source,"guardian.inventory",3) &&
            (!restore || permissions.has(source.player?.createCommandSourceStack() ?: source,"guardian.inventory.restore",4))
    }.getOrDefault(false)

    fun tick() {
        check(server.isSameThread)
        if(stopped || !automatic || !captureAllowed() || pending.size>=4 || otherBusy())return
        val now=nanoTime()
        if(scan.isEmpty()) {
            // nanoTime may have a negative origin and can wrap.
            if(nextScan?.let { now-it<0 }==true)return
            scan.addAll(server.playerList.players.map { it.uuid });nextScan=now+TimeUnit.SECONDS.toNanos(intervalSeconds.toLong())
        }
        val id=scan.pollFirst() ?: return
        if(id in pending)return
        val player=server.playerList.getPlayer(id) ?: return
        val started=System.nanoTime()
        val value=runCatching { MinecraftCheckpointCapture.observe(server,player,InventoryCheckpointReason.PERIODIC) }.getOrElse {
            lastSkipReason=(it.message ?: it.javaClass.simpleName).replace('\n',' ').take(180)
            null
        }
        lastNanos=System.nanoTime()-started;maxNanos=maxOf(maxNanos,lastNanos)
        if(value!=null)save(value.checkpoint,null) else skipped++
    }

    fun capture(source:CommandSourceStack,raw:String):Boolean {
        if(!permitted(source) || !permissions.has(source.player?.createCommandSourceStack() ?: source,"guardian.inventory.snapshot",3))return fail(source,"Manual inventory checkpoint permission is required.")
        if(!captureAllowed() || otherBusy() || pending.size>=4)return fail(source,"Capture is disabled or inventory recovery/capture is busy.")
        val name=CheckpointCommandFilters.capture(raw).getOrElse { return fail(source,it.message ?: "Use u:<player>.") }
        val player=server.playerList.players.firstOrNull { it.gameProfile.name.equals(name,true) || it.uuid.toString().equals(name,true) }
            ?: return fail(source,"That player must be online for a manual checkpoint.")
        if(player.uuid in pending)return fail(source,"That player's checkpoint is already being saved.")
        val started=System.nanoTime()
        val observed=runCatching { MinecraftCheckpointCapture.observe(server,player,InventoryCheckpointReason.MANUAL) }.getOrElse {
            return fail(source,it.message?.take(180) ?: "Player inventory cannot be captured.")
        }
        lastNanos=System.nanoTime()-started;maxNanos=maxOf(maxNanos,lastNanos)
        save(observed.checkpoint,source);return true
    }
    private fun save(value:InventoryCheckpoint,source:CommandSourceStack?) {
        check(pending.add(value.playerId))
        val submitted=System.nanoTime()
        fun timing() { lastSaveNanos=System.nanoTime()-submitted;maxSaveNanos=maxOf(maxSaveNanos,lastSaveNanos) }
        history.saveCheckpoint(value,limits,{ saved ->
            pending.remove(value.playerId);timing();if(stopped)return@saveCheckpoint
            if(saved.checkpointId==value.checkpointId)captured++ else unchanged++
            if(source!=null && permitted(source))source.sendSystemMessage(Component.literal("Guardian: inventory checkpoint saved for ${saved.playerName}: ${saved.checkpointId}."))
        },{ error ->
            pending.remove(value.playerId);timing();if(stopped)return@saveCheckpoint
            failures++
            if(source!=null && permitted(source))fail(source,error.message?.take(180) ?: "Checkpoint could not be saved.")
            val now=System.nanoTime()
            if(now-lastWarning>TimeUnit.SECONDS.toNanos(30)) {
                lastWarning=now;logger.warn("Inventory checkpoint persistence refused or failed; failures={}; {}",failures,error.javaClass.simpleName)
            }
        })
    }

    fun list(source:CommandSourceStack,raw:String):Boolean {
        if(!permitted(source))return fail(source,"Inventory history permission is required.")
        if(reading)return fail(source,"Checkpoint history is already being read; retry shortly.")
        val filter=CheckpointCommandFilters.parse(raw).getOrElse { return fail(source,it.message ?: "Invalid filters.") }
        reading=true
        fun load(player:UUID?) {
            val limit=filter.limit ?: 10;val page=filter.page ?: 1
            val after=filter.lookbackMillis?.let { (System.currentTimeMillis()-it).coerceAtLeast(0) }
            history.checkpointHeaders(player,limit,(page-1)*limit,after,{ rows ->
                reading=false;if(!permitted(source))return@checkpointHeaders
                source.sendSystemMessage(Component.literal("Guardian inventory checkpoints, page $page (${rows.size} results):"))
                rows.forEach { row ->
                    source.sendSystemMessage(Component.literal("${row.playerName} | ${java.time.Instant.ofEpochMilli(row.capturedAt)} | ${row.reason.name.lowercase().replace('_',' ')} ")
                        .append(CheckpointLinks.run("[View]","/guardian inventory view ${row.checkpointId}")))
                }
                if(rows.isEmpty()) {
                    val captureStatus=when {
                        !automatic -> "Automatic capture is disabled in the loaded server config."
                        !captureAllowed() -> "Automatic capture is enabled, but the logging master switch is disabled."
                        else -> "Automatic capture is enabled. Last capture skip: $lastSkipReason. See /guardian status."
                    }
                    source.sendSystemMessage(Component.literal("No inventory checkpoints match. $captureStatus Use /guardian inventory snapshot u:Player to test capture."))
                }
                source.sendSystemMessage(Component.literal("Use p:${page+1} for another page. View is read-only; restoration requires separate preview and confirmation."))
            },{ reading=false;if(permitted(source))fail(source,it.message?.take(180) ?: "Checkpoint history is unavailable.") })
        }
        val name=filter.actorName
        if(name==null)load(null) else {
            val id=runCatching { UUID.fromString(name).takeIf { it.toString().equals(name,true) } }.getOrNull()
                ?: server.playerList.players.firstOrNull { it.gameProfile.name.equals(name,true) }?.uuid
            if(id!=null)load(id) else history.checkpointPlayers(name,{ ids ->
                if(!permitted(source)) { reading=false;return@checkpointPlayers }
                if(ids.size!=1) { reading=false;fail(source,if(ids.isEmpty())"No checkpoints are recorded for that name." else "That name matches multiple UUIDs; use u:<UUID>.") } else load(ids.single())
            },{ reading=false;if(permitted(source))fail(source,"Player checkpoint lookup failed.") })
        }
        return true
    }

    fun view(source:CommandSourceStack,id:UUID):Boolean {
        val player=source.player ?: return fail(source,"Use checkpoint list from console; the read-only viewer requires a player.")
        if(!permitted(source) || reading || otherBusy())return fail(source,"Checkpoint viewer is unavailable or recovery is busy.")
        reading=true
        history.checkpoint(id,{ value ->
            reading=false;if(!permitted(source))return@checkpoint
            if(value==null)fail(source,"Checkpoint was not found or has expired.")
            else InventoryCheckpointViewer.open(player,value,{ permitted(source) && !otherBusy() },{ preview(player.createCommandSourceStack(),id) })
        },{ reading=false;if(permitted(source))fail(source,"Checkpoint image cannot be read; see the server log.") })
        return true
    }

    fun preview(source:CommandSourceStack,id:UUID):Boolean {
        if(!permitted(source,true))return fail(source,"Requires guardian.inventory.restore (operator level 4 by default).")
        if(reading || otherBusy())return fail(source,"Another inventory or recovery check is active.")
        reading=true
        history.checkpointRestoreSource(id,{ saved ->
            reading=false;if(!permitted(source,true))return@checkpointRestoreSource
            if(otherBusy()) { fail(source,"Another inventory or recovery check became active.");return@checkpointRestoreSource }
            try {
                val target=server.playerList.getPlayer(saved.checkpoint.playerId) ?: error("Target player is offline")
                val observed=MinecraftCheckpointCapture.observe(server,target,InventoryCheckpointReason.BEFORE_RESTORE,includeEquipment=saved.checkpoint.equipment!=null)
                val record=saved.record(UUID.randomUUID(),observed.checkpoint.capturedAt,observed.checkpoint)
                MinecraftCheckpointCapture.validateTarget(server,target,saved.checkpoint)
                source.sendSystemMessage(Component.literal("Guardian checkpoint preview: replace ${target.gameProfile.name}'s persistent inventory with ${java.time.Instant.ofEpochMilli(saved.checkpoint.capturedAt)}; ${record.entries.single().changes.size} slots would change. No items changed."))
                source.sendSystemMessage(Component.literal("Snapshot restore may recreate items that were moved elsewhere; it does not reverse outside inventories. The current inventory is saved as a before-restore checkpoint. Use /guardian inventory restore apply $id, then confirm its token."))
            } catch(error:Exception) { fail(source,error.message?.take(180) ?: "Checkpoint restore is unavailable.") }
        },{ reading=false;if(permitted(source,true))fail(source,it.message?.take(180) ?: "Checkpoint source is unavailable.") })
        return true
    }

    fun suggestIds(source:CommandSourceStack,builder:SuggestionsBuilder):CompletableFuture<Suggestions> {
        if(!permitted(source) || suggesting)return Suggestions.empty()
        suggesting=true
        val future=CompletableFuture<Suggestions>()
        history.checkpointHeaders(null,10,0,null,{ rows ->
            suggesting=false
            if(permitted(source))rows.map { it.checkpointId.toString() }.filter { it.startsWith(builder.remaining,true) }.forEach { builder.suggest(it) }
            future.complete(builder.build())
        },{ suggesting=false;future.complete(builder.build()) })
        return future
    }

    fun stop() { stopped=true;scan.clear();pending.clear() }
    private fun fail(source:CommandSourceStack,message:String):Boolean {
        source.sendFailure(Component.literal("Guardian inventory: "+message.filter { !it.isISOControl() && it.code!=167 }));return false
    }
}
