package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.config.GuardianConfig
import com.bareminimumstudios.guardian.domain.BlockPosition
import com.bareminimumstudios.guardian.domain.ResourceId
import com.bareminimumstudios.guardian.storage.query.BlockLookupQuery
import net.minecraft.world.entity.player.Player
import net.minecraft.server.level.ServerPlayer
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object BlockInspector {
    private val enabledPlayers = ConcurrentHashMap.newKeySet<UUID>()
    @Volatile private var historyProvider: () -> BlockHistoryService? = { null }
    @Volatile private var configProvider: () -> GuardianConfig? = { null }

    fun install(
        historyProvider: () -> BlockHistoryService?,
        configProvider: () -> GuardianConfig?
    ) {
        this.historyProvider = historyProvider
        this.configProvider = configProvider

    }

    fun toggle(player: ServerPlayer): Boolean {
        val uuid = player.uuid
        return if (enabledPlayers.remove(uuid)) false else {
            enabledPlayers.add(uuid)
            true
        }
    }

    fun set(player: ServerPlayer, enabled: Boolean): Boolean {
        if (enabled) enabledPlayers.add(player.uuid) else enabledPlayers.remove(player.uuid)
        return enabled
    }

    fun clear() = enabledPlayers.clear()

    fun isEnabled(player: ServerPlayer): Boolean = player.uuid in enabledPlayers

    fun inspectInteraction(player: Player, world: Level, pos: BlockPos, rightClick: Boolean = false): InteractionResult {
        if (world.isClientSide || player !is ServerPlayer || !isEnabled(player)) return InteractionResult.PASS
        val history = historyProvider() ?: run {
            player.sendSystemMessage(Component.literal("Guardian history is not available."))
            return InteractionResult.SUCCESS
        }
        val limit = configProvider()?.lookup?.inspectorResults?.get() ?: 10
        if (rightClick && world.getBlockEntity(pos) is net.minecraft.world.Container) {
            val owner = com.bareminimumstudios.guardian.domain.ItemSlotOwner.BlockContainer(
                ResourceId.parse(world.dimension().location().toString()), BlockPosition(pos.x, pos.y, pos.z))
            history.lookupContainers(com.bareminimumstudios.guardian.storage.query.ContainerLookupQuery(owner.dimension, owner.position, limit = limit),
                { rows -> ContainerHistoryFormatter.lines(rows, focus = owner).forEach(player::sendSystemMessage) },
                { player.sendSystemMessage(Component.literal("Guardian container lookup failed; see the server log.")) })
            return InteractionResult.SUCCESS
        }
        val query = BlockLookupQuery(
            dimension = ResourceId.parse(world.dimension().location().toString()),
            position = BlockPosition(pos.x, pos.y, pos.z),
            limit = limit
        )
        history.lookup(
            query,
            onSuccess = { rows -> BlockHistoryFormatter.lines(rows).forEach(player::sendSystemMessage) },
            onFailure = { player.sendSystemMessage(Component.literal("Guardian inspector lookup failed; see the server log.")) }
        )
        return InteractionResult.SUCCESS
    }
}
