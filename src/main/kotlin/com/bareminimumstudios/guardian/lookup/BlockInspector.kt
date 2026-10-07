package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.config.GuardianConfig
import com.bareminimumstudios.guardian.domain.BlockPosition
import com.bareminimumstudios.guardian.domain.ResourceId
import com.bareminimumstudios.guardian.storage.query.BlockLookupQuery
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object BlockInspector {
    private val enabledPlayers = ConcurrentHashMap.newKeySet<UUID>()
    @Volatile private var historyProvider: () -> BlockHistoryService? = { null }
    @Volatile private var configProvider: () -> GuardianConfig? = { null }
    @Volatile private var installed = false

    fun install(
        historyProvider: () -> BlockHistoryService?,
        configProvider: () -> GuardianConfig?
    ) {
        this.historyProvider = historyProvider
        this.configProvider = configProvider
        if (installed) return
        installed = true

        AttackBlockCallback.EVENT.register(AttackBlockCallback { player, world, _, pos, _ ->
            inspectInteraction(player, world, pos)
        })
        UseBlockCallback.EVENT.register(UseBlockCallback { player, world, _, hit ->
            inspectInteraction(player, world, hit.blockPos)
        })
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            enabledPlayers.remove(handler.player.uuid)
        }
    }

    fun toggle(player: ServerPlayerEntity): Boolean {
        val uuid = player.uuid
        return if (enabledPlayers.remove(uuid)) false else {
            enabledPlayers.add(uuid)
            true
        }
    }

    fun set(player: ServerPlayerEntity, enabled: Boolean): Boolean {
        if (enabled) enabledPlayers.add(player.uuid) else enabledPlayers.remove(player.uuid)
        return enabled
    }

    fun clear() = enabledPlayers.clear()

    fun isEnabled(player: ServerPlayerEntity): Boolean = player.uuid in enabledPlayers

    private fun inspectInteraction(player: PlayerEntity, world: World, pos: BlockPos): ActionResult {
        if (world.isClient || player !is ServerPlayerEntity || !isEnabled(player)) return ActionResult.PASS
        val history = historyProvider() ?: run {
            player.sendMessage(Text.literal("Guardian history is not available."))
            return ActionResult.SUCCESS
        }
        val limit = configProvider()?.lookup?.inspectorResults?.get() ?: 10
        val query = BlockLookupQuery(
            dimension = ResourceId.parse(world.registryKey.value.toString()),
            position = BlockPosition(pos.x, pos.y, pos.z),
            limit = limit
        )
        history.lookup(
            query,
            onSuccess = { rows -> BlockHistoryFormatter.lines(rows).forEach(player::sendMessage) },
            onFailure = { player.sendMessage(Text.literal("Guardian inspector lookup failed; see the server log.")) }
        )
        return ActionResult.SUCCESS
    }
}
