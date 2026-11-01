package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.config.GuardianConfig
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.permission.PermissionService
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.network.chat.Component
import java.util.UUID

/** Explicit session-only subscriptions. No content is buffered for an offline or revoked viewer. */
object ActivitySpy {
    private data class Binding(val server:MinecraftServer,val config:GuardianConfig,val permissions:PermissionService)
    private data class Subscription(val filters:MutableMap<PlayerActivityKind,com.bareminimumstudios.guardian.command.SpyFilter>,var tick:Int=Int.MIN_VALUE,var count:Int=0,var suppressed:Long=0)
    private var binding:Binding?=null
    private val viewers=linkedMapOf<UUID,Subscription>()
    fun install(server:MinecraftServer,config:GuardianConfig,permissions:PermissionService) { clear();binding=Binding(server,config,permissions) }
    fun clear() { viewers.clear();binding=null }
    fun forget(id:UUID) { viewers.remove(id) }
    private fun permission(kind:PlayerActivityKind)=if(kind==PlayerActivityKind.WHISPER)"guardian.whispers" else "guardian.commands"
    private fun active(b:Binding,kind:PlayerActivityKind)=b.config.general.enabled.get() && b.config.logging.enabled.get() && b.config.activity.liveSpy.get() &&
        (if(kind==PlayerActivityKind.WHISPER)b.config.activity.whispers.get() else b.config.activity.commands.get())
    fun set(player:ServerPlayer,kind:PlayerActivityKind,on:Boolean,filter:com.bareminimumstudios.guardian.command.SpyFilter=com.bareminimumstudios.guardian.command.SpyFilter()):Boolean {
        val b=binding ?: return false
        if(!b.server.isSameThread || player.server!==b.server || b.server.playerList.getPlayer(player.uuid)!==player || kind !in setOf(PlayerActivityKind.WHISPER,PlayerActivityKind.COMMAND))return false
        if(!on) { viewers[player.uuid]?.let { it.filters.remove(kind);if(it.filters.isEmpty())viewers.remove(player.uuid) };return true }
        val source=player.createCommandSourceStack()
        if(!active(b,kind) || !b.permissions.has(source,"guardian.spy",3) || !b.permissions.has(source,permission(kind),3) || viewers.size>=256 && player.uuid !in viewers)return false
        viewers.getOrPut(player.uuid) { Subscription(mutableMapOf()) }.filters[kind]=filter
        return true
    }
    fun status(player:ServerPlayer):String {
        val b=binding ?: return "Guardian spy unavailable."
        if(!b.server.isSameThread || b.server.playerList.getPlayer(player.uuid)!==player || !b.permissions.has(player.createCommandSourceStack(),"guardian.spy",3))return "Guardian spy unavailable."
        val current=viewers[player.uuid] ?: return "Guardian spy: off."
        current.filters.keys.removeIf { !active(b,it) || !b.permissions.has(player.createCommandSourceStack(),permission(it),3) }
        if(current.filters.isEmpty()) { viewers.remove(player.uuid);return "Guardian spy: off." }
        return "Guardian spy: "+current.filters.entries.joinToString("; ") { (kind,f)->kind.name.lowercase()+" ("+f.description(kind==PlayerActivityKind.COMMAND)+")" }+". Suppressed since last delivery: "+current.suppressed
    }
    fun publish(server:MinecraftServer,row:PlayerActivity) {
        val b=binding ?: return
        if(b.server!==server || !server.isSameThread || row.kind !in setOf(PlayerActivityKind.WHISPER,PlayerActivityKind.COMMAND))return
        if(!active(b,row.kind)) { viewers.values.forEach { it.filters.remove(row.kind) };viewers.entries.removeIf { it.value.filters.isEmpty() };return }
        var rendered:Component?=null
        val iterator=viewers.iterator()
        while(iterator.hasNext()) {
            val (id,subscription)=iterator.next()
            val player=server.playerList.getPlayer(id)
            if(player==null) { iterator.remove();continue }
            val source=player.createCommandSourceStack()
            if(!b.permissions.has(source,"guardian.spy",3)) { iterator.remove();continue }
            subscription.filters.keys.removeIf { !b.permissions.has(source,permission(it),3) }
            if(subscription.filters.isEmpty()) { iterator.remove();continue }
            if(subscription.filters[row.kind]?.matches(row)!=true)continue
            if(subscription.tick!=server.tickCount) {
                if(subscription.suppressed>0)player.sendSystemMessage(Component.literal("Guardian spy: ${subscription.suppressed} updates suppressed in the previous burst. Use history to catch up."))
                subscription.suppressed=0;subscription.tick=server.tickCount;subscription.count=0
            }
            if(subscription.count++>=20) {
                subscription.suppressed++
                if(subscription.count==21)player.sendSystemMessage(Component.literal("Guardian spy: further updates this tick were suppressed. Use history for recorded entries."))
                continue
            }
            if(rendered==null)rendered=com.bareminimumstudios.guardian.lookup.ActivityFormatter.line(row,spy=true)
            player.sendSystemMessage(rendered)
        }
    }
}
