package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.ItemSlotOwner
import com.bareminimumstudios.guardian.logging.*
import com.bareminimumstudios.guardian.lookup.BlockHistoryService
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Operator view only. It never replays items, changes phases or releases persistent claims. */
class ItemRecoveryService(private val server: MinecraftServer,private val history: BlockHistoryService,private val pipeline: BufferedLogPipeline,private val otherBusy: () -> Boolean) {
    private val players=com.bareminimumstudios.guardian.platform.minecraft.MinecraftSavedPlayerReader()
    private var active: Session?=null
    private var recent: List<String> = emptyList()
    fun isBusy()=active!=null
    fun suggestions()=recent
    fun request(source: CommandSourceStack,id: UUID?=null,saved: Boolean=false): Boolean {
        require(!saved || id!=null)
        if(active!=null || otherBusy()) { source.sendFailure(Component.literal("Guardian already has an item check in progress."));return false }
        val session=Session(source,id,saved);active=session
        source.sendSystemMessage(Component.literal("Guardian: checking item recovery journal; no items will be changed..."))
        fence(session) {
            if(id==null) history.recoveryHeaders({ headers ->
                if(active !== session) return@recoveryHeaders
                recent=headers.map { it.operationId.toString() }
                source.sendSystemMessage(Component.literal("Guardian unfinished item journals: ${headers.size} shown (maximum 10)."))
                headers.forEach { source.sendSystemMessage(Component.literal("${it.operationId} | ${it.phase}").withStyle { style -> style.withClickEvent(net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND,"/guardian rollback-items recovery ${it.operationId}")) }) }
                source.sendSystemMessage(Component.literal("Use /guardian rollback-items recovery <operation UUID>. Read-only; no claims cleared."));active=null
            }, { refuse(session,"Journal listing failed or persistent storage is unavailable.") })
            else history.recoveryRecord(id,{ record ->
                if(active !== session) return@recoveryRecord
                if(record==null) { refuse(session,"Journal entry not found.");return@recoveryRecord }
                val check=runCatching { ItemRecoveryCheck(record) }.getOrElse { refuse(session,"Journal plan is unsupported or inconsistent.");return@recoveryRecord }
                session.watch=runCatching { pipeline.observeOwners(check.owners) }.getOrElse { refuse(session,"Owner observation is unavailable.");return@recoveryRecord }
                session.check=check;session.owners.addAll(check.owners);session.ready=true
            }, { refuse(session,"Journal entry not found or could not be read; see server log.") })
        }
        return true
    }
    fun tick() {
        val session=active ?: return
        if(System.nanoTime()-session.started>TimeUnit.SECONDS.toNanos(10)) { refuse(session,"Recovery observation timed out.");return }
        if(!session.ready) return
        val owner=session.owners.pollFirst()
        if(owner!=null) {
            if(session.saved) { when(owner) { is ItemSlotOwner.BlockContainer -> readSaved(session,owner);is ItemSlotOwner.PlayerInventory -> readSavedPlayer(session,owner);else -> { session.check!!.accept(owner,null) } };return }
            val observation=runCatching { MinecraftInventoryObservation.read(server,owner,session.check!!.addresses(owner)) }.getOrNull()
            session.check!!.accept(owner,observation?.snapshot)
            if(observation!=null) session.bindings[owner]=observation
            return
        }
        session.ready=false
        fence(session) { history.recoveryRecord(session.id!!,{ fresh ->
            if(active !== session) return@recoveryRecord
            val check=session.check!!
            if(fresh==null || fresh.phase!=check.record.phase || fresh.createdAt!=check.record.createdAt || fresh.entries.map { it.transactionId }!=check.record.entries.map { it.transactionId } || fresh.entries.zip(check.record.entries).any { (a,b) -> a.changes!=b.changes }) { refuse(session,"Journal changed during observation; try again.");return@recoveryRecord }
            val changed=session.bindings.filterValues { !it.isCurrent() }.keys + (session.watch?.changedOwners() ?: emptySet())
            val observed=check.observe(changed)
            session.source.sendSystemMessage(Component.literal("Guardian item recovery ${fresh.operationId}: phase=${fresh.phase}, ${if(session.saved) "saved" else "observed"}=${observed.name}."))
            session.source.sendSystemMessage(Component.literal(if(session.saved) "Saved-slot comparison: ${observed.name}. Saved data was sampled without saving or loading live chunks or players. This is not coordinated save completion." else label(observed)))
            session.source.sendSystemMessage(Component.literal("Read-only observation: no items changed, no phase changed, no claims cleared. This is not permission to replay items."))
            session.watch?.close();active=null
        }, { refuse(session,"Journal recheck failed; see server log.") }) }
    }
    private fun readSaved(session: Session,owner: ItemSlotOwner.BlockContainer) {
        session.ready=false
        val key=net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.ResourceLocation.parse(owner.dimension.toString()))
        val world=server.getLevel(key)
        if(world==null) { session.check!!.accept(owner,null);session.ready=true;return }
        val pos=net.minecraft.world.level.ChunkPos(owner.position.x shr 4,owner.position.z shr 4)
        try {
            com.bareminimumstudios.guardian.platform.minecraft.MinecraftSavedChunkReader.read(world.chunkSource.chunkMap,pos).whenComplete { tag,error -> server.execute {
                if(active !== session) return@execute
                val snapshot=if(error!=null || tag==null || tag.isEmpty) null else runCatching {
                    com.bareminimumstudios.guardian.platform.minecraft.MinecraftSavedContainerDecoder.decode(tag.get(),owner,session.check!!.addresses(owner),world.registryAccess())
                }.getOrNull()
                session.check!!.accept(owner,snapshot);session.ready=true
            } }
        } catch(_: Exception) { session.check!!.accept(owner,null);session.ready=true }
    }
    private fun readSavedPlayer(session: Session,owner: ItemSlotOwner.PlayerInventory) {
        session.ready=false
        val folder=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR)
        players.read(folder,owner.playerId).whenComplete { tag,error -> server.execute {
            if(active !== session) return@execute
            val snapshot=if(error!=null || tag==null || tag.isEmpty) null else runCatching {
                com.bareminimumstudios.guardian.platform.minecraft.MinecraftSavedPlayerDecoder.decode(tag.get(),owner,session.check!!.addresses(owner),server.registryAccess())
            }.getOrNull()
            session.check!!.accept(owner,snapshot);session.ready=true
        } }
    }
    /** Cancels reporting only; started reads and persistent ownership are untouched. */
    fun cancel(): Boolean {
        val session=active ?: return false
        refuse(session,"Check cancelled by an operator.")
        return true
    }

    fun stop() { val session=active;active=null;session?.barrier?.cancel();session?.watch?.close();players.close();recent=emptyList() }
    private fun fence(session: Session,after: () -> Unit) {
        session.barrier=pipeline.writeBarrier()
        session.barrier!!.result.whenComplete { _,error -> server.execute {
            if(active !== session) return@execute
            if(error!=null) refuse(session,"Accepted audit writes could not be confirmed.") else after()
        } }
    }
    private fun refuse(session: Session,message: String) { if(active !== session) return;active=null;session.barrier?.cancel();session.watch?.close();session.source.sendFailure(Component.literal("Guardian item recovery: $message No items changed or claims cleared.")) }
    private fun label(value: ItemRecoveryObservation)=when(value) {
        ItemRecoveryObservation.ORIGINAL -> "Observed slots match the original state; apply or save completion is not established."
        ItemRecoveryObservation.RESTORED -> "Observed slots match the restored state; durable world/player saves are not established."
        ItemRecoveryObservation.BOTH -> "Original and restored states are indistinguishable; the result is unresolved."
        ItemRecoveryObservation.PARTIAL -> "Observed slots contain a mixture of original/restored states; the result is unresolved."
        ItemRecoveryObservation.CONFLICT -> "Observed items or components match neither expected state; the result is unresolved."
        ItemRecoveryObservation.UNAVAILABLE -> "An owner is offline/busy, missing/unloaded, sealed, replaced or had captured activity during checking; the result is unresolved."
    }
    private class Session(val source: CommandSourceStack,val id: UUID?,val saved: Boolean) {
        val started=System.nanoTime();var barrier: AuditWriteBarrier?=null
        var check: ItemRecoveryCheck?=null;var ready=false
        var watch: AuditOwnerObservation?=null
        val owners=ArrayDeque<ItemSlotOwner>()
        val bindings=linkedMapOf<ItemSlotOwner,MinecraftInventoryObservation>()
    }
}
