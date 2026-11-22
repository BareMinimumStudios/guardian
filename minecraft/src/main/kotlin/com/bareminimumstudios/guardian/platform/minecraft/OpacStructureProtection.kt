package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.claims.*
import com.bareminimumstudios.guardian.config.GuardianConfig
import com.bareminimumstudios.guardian.domain.ResourceId
import com.bareminimumstudios.guardian.permission.PermissionService
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.UUID

/** A request identifies the actual connection, not merely the UUID being claimed as. */
internal class OpacRequesterCapture internal constructor(
    val server: WeakReference<MinecraftServer>,val player: WeakReference<ServerPlayer>,val connection: WeakReference<Any>
)

/** Optional OPAC 0.33 integration. No OPAC classes are linked when the mod is absent. */
internal object OpacStructureProtection {
    private class Binding(
        val server: MinecraftServer,val permissions: PermissionService,val manager: Any,
        val loaded: Field,val getClaim: Method,val playerData: Method,val ensureAdmin: Method,val permissionHandler: Any,
        val admin: Method,val serverMode: Method,val serverOwner: UUID,val replaceWith: Field,
        val result: Constructor<*>,val forbidden: Any,val pending: Any,
        val source: MinecraftStructureChunkSource,val lookup: StructureFootprintLookup,val radius: Int
    )
    @Volatile private var binding: Binding?=null
    private val requester=ThreadLocal<OpacRequesterCapture?>()
    @Volatile private var armed=false
    private var startupResult: Constructor<*>?=null
    private var startupPending: Any?=null

    /** Fence claiming before other mods finish starting; persisted claim loading remains possible. */
    fun prepare(config: GuardianConfig.StructureClaims) {
        check(binding==null && !armed)
        if(!config.enabled.get())return
        val version=com.bareminimumstudios.guardian.checkpoint.EquipmentModDiscovery.installed()["openpartiesandclaims"]
        require(version=="0.33.0") { "Structure claim protection requires Open Parties and Claims 0.33.0; found ${version ?: "not installed"}" }
        val resultType=type("xaero.pac.common.claims.result.api.ClaimResult")
        val resultEnum=type("xaero.pac.common.claims.result.api.ClaimResult${'$'}Type")
        startupResult=resultType.constructors.single { it.parameterCount==3 && it.parameterTypes[1]==resultEnum && Component::class.java.isAssignableFrom(it.parameterTypes[2]) }
        startupPending=resultEnum.enumConstants.single { (it as Enum<*>).name=="ADDON_INTERRUPTS" }
        armed=true
    }

    fun start(server: MinecraftServer,config: GuardianConfig.StructureClaims,permissions: PermissionService) {
        check(binding==null)
        if(!config.enabled.get())return
        val version=com.bareminimumstudios.guardian.checkpoint.EquipmentModDiscovery.installed()["openpartiesandclaims"]
        require(version=="0.33.0") { "Structure claim protection requires Open Parties and Claims 0.33.0; found ${version ?: "not installed"}" }
        val ids=config.structures.get().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.map(ResourceId::parse).toSet()
        require(ids.isNotEmpty() && ids.size<=64) { "Configure between 1 and 64 structure IDs before enabling structure claim protection" }
        val radius=config.bufferBlocks.get()
        require(radius in 0..256)
        val api=type("xaero.pac.common.server.api.OpenPACServerAPI").getMethod("get",MinecraftServer::class.java).invoke(null,server)
        val manager=api.javaClass.getMethod("getServerClaimsManager").invoke(api)
        require(manager is OpacClaimHooks && server.commands is OpacCommandHooks &&
            OpacChunkHooks::class.java.isAssignableFrom(net.minecraft.world.level.chunk.ChunkAccess::class.java)) {
            "Required OPAC structure protection hooks were not applied; no protected claiming can be enabled"
        }
        val area=type("xaero.pac.common.server.claims.player.task.PlayerAreaClaimActionSpreadoutTask")
        val replacement=type("xaero.pac.common.server.claims.player.task.PlayerClaimReplaceSpreadoutTask")
        val transfer=type("xaero.pac.common.server.claims.player.task.PlayerSubClaimTransferSpreadoutTask")
        val handler=type("xaero.pac.common.server.claims.player.request.PlayerClaimActionRequestHandler")
        require(OpacTaskHooks::class.java.isAssignableFrom(area) && OpacReplacementHooks::class.java.isAssignableFrom(replacement) &&
            OpacReplacementHooks::class.java.isAssignableFrom(transfer) && OpacRequestHooks::class.java.isAssignableFrom(handler)) {
            "Required OPAC queued-task or requester hooks are unavailable"
        }
        val playerDataType=type("xaero.pac.common.server.player.data.api.ServerPlayerDataAPI")
        val permissionHandler=manager.javaClass.getMethod("getPermissionHandler").invoke(manager)
        val getClaim=manager.javaClass.methods.first { it.name=="get" && it.parameterCount==3 &&
            it.parameterTypes[1]==Int::class.javaPrimitiveType && it.parameterTypes[2]==Int::class.javaPrimitiveType }
        val resultType=type("xaero.pac.common.claims.result.api.ClaimResult")
        val resultEnum=type("xaero.pac.common.claims.result.api.ClaimResult${'$'}Type")
        val result=resultType.constructors.single { it.parameterCount==3 && it.parameterTypes[1]==resultEnum &&
            Component::class.java.isAssignableFrom(it.parameterTypes[2]) }
        fun enum(name:String)=checkNotNull(resultEnum.enumConstants.firstOrNull { (it as Enum<*>).name==name })
        val loaded=field(manager.javaClass,"loaded")
        require(loaded.type==Boolean::class.javaPrimitiveType)
        val source=MinecraftStructureChunkSource(server,ids)
        binding=Binding(server,permissions,manager,loaded,getClaim,
            playerDataType.getMethod("from",ServerPlayer::class.java),
            permissionHandler.javaClass.getMethod("ensureAdminModeStatusPermission",ServerPlayer::class.java,playerDataType),permissionHandler,
            playerDataType.getMethod("isClaimsAdminMode"),playerDataType.getMethod("isClaimsServerMode"),
            type("xaero.pac.common.claims.api.SpecialClaimOwners").getField("SERVER").get(null) as UUID,
            field(replacement,"with"),result,enum("ADDON_FORBIDS"),enum("ADDON_INTERRUPTS"),source,
            StructureFootprintLookup(StructureClaimPolicy(ids,radius),source),radius)
        org.slf4j.LoggerFactory.getLogger("Guardian").info("Structure claim protection ready: {} structure IDs, {}-block buffer, OPAC {}",ids.size,radius,version)
    }

    fun tick() { binding?.lookup?.tick() }
    fun stop() {
        armed=false
        startupResult=null;startupPending=null
        val current=binding ?: return
        binding=null
        current.lookup.close();current.source.close()
    }
    fun status():String?=binding?.let { current ->
        "OPAC structures: buffer=${current.radius} cached=${current.lookup.cachedChunks} queued=${current.lookup.queuedChunks} reading=${current.lookup.readingChunks}"
    }
    @JvmStatic fun invalidateChunk(pos:ChunkPos)=invalidateChunk(pos.x,pos.z)
    @JvmStatic fun invalidateChunk(x:Int,z:Int) { binding?.lookup?.invalidateChunk(x,z) }

    @JvmStatic fun captureRequester():OpacRequesterCapture?=requester.get()
    @JvmStatic fun withRequester(player:ServerPlayer?,action:Runnable) {
        val current=binding
        if(current==null) { action.run();return }
        val capture=if(player!=null && current.server.isSameThread &&
            current.server.playerList.getPlayer(player.uuid)===player)
            OpacRequesterCapture(WeakReference(current.server),WeakReference(player),WeakReference(player.connection)) else null
        withTask(capture,action)
    }
    @JvmStatic fun withTask(capture:OpacRequesterCapture?,action:Runnable) {
        val previous=requester.get()
        if(capture==null)requester.remove() else requester.set(capture)
        try { action.run() } finally { if(previous==null)requester.remove() else requester.set(previous) }
    }

    private fun authority(current:Binding,owner:UUID):StructureClaimRequester {
        val token=requester.get() ?: return NO_BYPASS
        val player=token.player.get() ?: return NO_BYPASS
        if(token.server.get()!==current.server || current.server.playerList.getPlayer(player.uuid)!==player ||
            player.connection!==token.connection.get() || player.isRemoved)return NO_BYPASS
        val data=current.playerData.invoke(null,player)
        current.ensureAdmin.invoke(current.permissionHandler,player,data)
        return StructureClaimRequester(
            current.permissions.has(player.createCommandSourceStack(),"guardian.claims.structure.bypass",4),
            current.admin.invoke(data)==true,current.serverMode.invoke(data)==true && owner==current.serverOwner)
    }

    private fun decision(current:Binding,manager:Any,dimension:Any,owner:UUID,x:Int,z:Int,existing:Boolean):StructureClaimDecision {
        if(manager!==current.manager || !current.server.isSameThread)return StructureClaimDecision.LOOKUP_PENDING
        if(existing && !current.loaded.getBoolean(manager))return StructureClaimDecision.PASS // native persisted-claim reconstruction only
        if(existing) {
            val claim=current.getClaim.invoke(manager,dimension,x,z)
            if(claim!=null && playerId(claim)==owner)return StructureClaimDecision.PASS
        }
        return current.lookup.check(authority(current,owner),ResourceId.parse(dimension.toString()),x,z)
    }

    /** Returns OPAC's normal denial result; never grants a permission override. */
    @JvmStatic fun beforeClaim(manager:Any,dimension:Any,owner:UUID,x:Int,z:Int):Any? {
        val current=binding ?: return if(armed)checkNotNull(startupResult).newInstance(null,startupPending,
            Component.literal("Guardian structure claim protection is starting. Try again shortly.")) else null
        val decision=runCatching { decision(current,manager,dimension,owner,x,z,false) }.getOrDefault(StructureClaimDecision.LOOKUP_PENDING)
        if(decision==StructureClaimDecision.PASS)return null
        return current.result.newInstance(null,if(decision==StructureClaimDecision.LOOKUP_PENDING)current.pending else current.forbidden,Component.literal(reason(current,decision)))
    }

    /** Raw and forced API writes cannot bypass the helper's denial. Existing ownership is retained. */
    @JvmStatic fun directClaim(manager:Any,dimension:Any,owner:UUID,x:Int,z:Int) {
        val current=binding ?: run {
            if(armed)check(manager.javaClass.getMethod("isLoaded").invoke(manager)==false) { "Guardian structure claim protection is starting" }
            return
        }
        val decision=runCatching { decision(current,manager,dimension,owner,x,z,true) }.getOrDefault(StructureClaimDecision.LOOKUP_PENDING)
        check(decision==StructureClaimDecision.PASS) { reason(current,decision) }
    }

    /** Runs before a replacement changes ownership, preserving the old claim on refusal. */
    @JvmStatic fun allowReplacement(task:Any,dimension:Any?,pos:ChunkPos):Boolean {
        val current=binding ?: return !armed
        if(dimension==null)return false
        return runCatching {
            val target=current.replaceWith.get(task) ?: return@runCatching true // clearing claims remains allowed
            decision(current,current.manager,dimension,playerId(target),pos.x,pos.z,false)==StructureClaimDecision.PASS
        }.getOrDefault(false)
    }
    private fun reason(current:Binding,decision:StructureClaimDecision)=if(decision==StructureClaimDecision.PROTECTED_STRUCTURE)
        "Guardian: this chunk overlaps a protected structure or its ${current.radius}-block buffer."
    else "Guardian is checking nearby structures. Try again shortly; visit the area if it has not been generated or updated."
    private fun playerId(claim:Any)=claim.javaClass.getMethod("getPlayerId").invoke(claim) as UUID
    private fun type(name:String)=Class.forName(name,false,OpacStructureProtection::class.java.classLoader)
    private fun field(type:Class<*>,name:String):Field {
        var current:Class<*>?=type
        while(current!=null) {
            try { return current.getDeclaredField(name).also { it.isAccessible=true } } catch(_:NoSuchFieldException) { current=current.superclass }
        }
        error("Required OPAC field $name is unavailable")
    }
    private val NO_BYPASS=StructureClaimRequester(false,false,false)
}
