package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.config.GuardianConfig
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.query.*
import com.bareminimumstudios.guardian.permission.PermissionService
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
    private data class Selection(val owner: ItemSlotOwner.BlockContainer, val items: Boolean, val oldestFirst: Boolean = false)
    private data class Click(val pos: BlockPos, val right: Boolean, val tick: Long)
    private val lastClicks = ConcurrentHashMap<UUID, Click>()
    private val selections = ConcurrentHashMap<UUID, Selection>()
    @Volatile private var historyProvider: () -> BlockHistoryService? = { null }
    @Volatile private var configProvider: () -> GuardianConfig? = { null }
    @Volatile private var permissions: PermissionService? = null
    fun install(historyProvider: () -> BlockHistoryService?, configProvider: () -> GuardianConfig?, permissions: PermissionService) {
        this.historyProvider = historyProvider; this.configProvider = configProvider; this.permissions = permissions
    }
    fun toggle(player: ServerPlayer): Boolean = if (enabledPlayers.remove(player.uuid)) { selections.remove(player.uuid); lastClicks.remove(player.uuid); false } else { enabledPlayers.add(player.uuid); true }
    fun set(player: ServerPlayer, enabled: Boolean): Boolean {
        if (enabled) enabledPlayers.add(player.uuid) else { enabledPlayers.remove(player.uuid); selections.remove(player.uuid); lastClicks.remove(player.uuid) }
        return enabled
    }
    fun clear() { enabledPlayers.clear(); selections.clear(); lastClicks.clear() }
    fun isEnabled(player: ServerPlayer): Boolean = player.uuid in enabledPlayers

    @JvmStatic fun intercept(player: ServerPlayer, pos: BlockPos, rightClick: Boolean): Boolean {
        if (!player.server.isSameThread || !isEnabled(player)) return false
        if (!authorized(player)) { set(player, false); player.sendSystemMessage(Component.literal("Guardian inspection disabled: permission was removed.")); return true }
        val level = player.serverLevel()
        if (!player.canInteractWithBlock(pos, 1.0) || level.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4) == null) return true
        val tick = level.gameTime
        val last = lastClicks[player.uuid]
        if (last != null && last.pos == pos && last.right == rightClick && tick - last.tick in 0..2) return true
        lastClicks[player.uuid] = Click(pos.immutable(), rightClick, tick)
        inspectInteraction(player, level, pos, rightClick)
        return true
    }
    private fun authorized(player: ServerPlayer) = runCatching { permissions?.has(player.createCommandSourceStack(), "guardian.inspect", 2) == true }.getOrDefault(false)

    fun inspectInteraction(player: Player, world: Level, pos: BlockPos, rightClick: Boolean = false): InteractionResult {
        if (world.isClientSide || player !is ServerPlayer || !isEnabled(player)) return InteractionResult.PASS
        if (!authorized(player)) return InteractionResult.SUCCESS
        val owner = ItemSlotOwner.BlockContainer(ResourceId.parse(world.dimension().location().toString()), BlockPosition(pos.x, pos.y, pos.z))
        selections[player.uuid] = Selection(owner, rightClick && world.getBlockEntity(pos) is net.minecraft.world.Container,
            selections[player.uuid]?.oldestFirst ?: false)
        showPage(player, 1)
        return InteractionResult.SUCCESS
    }
    fun setOrder(player: ServerPlayer, oldest: Boolean) {
        val selection = selections[player.uuid] ?: return
        selections[player.uuid] = selection.copy(oldestFirst = oldest); showPage(player, 1)
    }
    fun showPage(player: ServerPlayer, page: Int) {
        if (!isEnabled(player) || !authorized(player)) return
        val selection = selections[player.uuid] ?: run { player.sendSystemMessage(Component.literal("Guardian: inspect a block first.")); return }
        val history = historyProvider() ?: run { player.sendSystemMessage(Component.literal("Guardian history is not available.")); return }
        val limit = (configProvider()?.lookup?.inspectorResults?.get() ?: 5).coerceAtMost(5)
        val owner = selection.owner
        val footer = { hasNext: Boolean -> HistoryNavigation.footer(page, hasNext, selection.oldestFirst,
            { next -> "/guardian inspect page $next" }, "/guardian inspect order ${if (selection.oldestFirst) "newest" else "oldest"}") }
        if (selection.items) history.lookupContainers(ContainerLookupQuery(owner.dimension, owner.position, limit = limit,
            offset = (page - 1) * limit, oldestFirst = selection.oldestFirst), { rows ->
            if (isEnabled(player) && authorized(player) && selections[player.uuid] == selection) {
                ContainerHistoryFormatter.lines(rows, focus = owner).forEach(player::sendSystemMessage)
                player.sendSystemMessage(footer(rows.size == limit))
            }
        }, { if (isEnabled(player) && authorized(player) && selections[player.uuid] == selection) player.sendSystemMessage(Component.literal("Guardian container lookup failed; see server log.")) })
        else history.lookup(BlockLookupQuery(owner.dimension, owner.position, limit = limit,
            offset = (page - 1) * limit, oldestFirst = selection.oldestFirst), { rows ->
            if (isEnabled(player) && authorized(player) && selections[player.uuid] == selection) {
                BlockHistoryFormatter.lines(rows).forEach(player::sendSystemMessage); player.sendSystemMessage(footer(rows.size == limit))
            }
        }, { if (isEnabled(player) && authorized(player) && selections[player.uuid] == selection) player.sendSystemMessage(Component.literal("Guardian block lookup failed; see server log.")) })
    }
}
