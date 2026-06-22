package xyz.naomieow.guardian.action

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import xyz.naomieow.guardian.Guardian
import xyz.naomieow.guardian.database.table.BlockStateModification
import xyz.naomieow.guardian.database.table.BlockStateModificationData
import xyz.naomieow.guardian.event.PlayerBlockEvent
import xyz.naomieow.guardian.ext.inspectMode
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

object BlockStateAction : Action {
    private var logQueue = mutableListOf<BlockStateModificationData>()

    override fun register() {
        // Logging
        PlayerBlockEvent.Break.AFTER.subscribe(listener = ::logBreak)
        PlayerBlockEvent.Place.BEFORE.subscribe(listener = ::logPlaceEnqueue)
        PlayerBlockEvent.Place.CANCELLED.subscribe(listener = ::cancelLogPlace)
        PlayerBlockEvent.Place.AFTER.subscribe(listener = ::logPlaceDequeue)
        // Inspecting
        PlayerBlockEvent.Place.BEFORE.subscribe(listener = ::inspectPlace)
        PlayerBlockEvent.Break.BEFORE.subscribe(listener = ::inspectBreak)
    }

    @OptIn(ExperimentalTime::class)
    private fun logBreak(
        level: Level,
        player: Player,
        pos: BlockPos,
        state: BlockState,
        blockEntity: BlockEntity?
    ) {
        if (Guardian.actionConfig.player.breakBlock) {
            transaction {
                SchemaUtils.create(BlockStateModification)

                BlockStateModification.insert {
                    it[performedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                    it[oldState] = state
                    it[newState] = level.getBlockState(pos)
                    it[playerUUID] = player.uuid.toString()
                    it[playerName] = player.name.string
                    it[posX] = pos.x
                    it[posY] = pos.y
                    it[posZ] = pos.z
                    it[actionType] = ActionType.BLOCK_BREAK
                }
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun logPlaceEnqueue(
        level: Level,
        player: Player,
        pos: BlockPos,
        state: BlockState
    ): Boolean {
        if (Guardian.actionConfig.player.placeBlock) {
            logQueue.addLast(BlockStateModificationData(
                Clock.System.now().toLocalDateTime(TimeZone.UTC),
                level.getBlockState(pos),
                state,
                player.uuid.toString(),
                player.name.string,
                pos,
                ActionType.BLOCK_PLACE
            ))
        }
        return false
    }

    private fun cancelLogPlace(
        level: Level,
        player: Player,
        pos: BlockPos,
        state: BlockState
    ) {
        logQueue.removeIf {
            it.playerUUID == player.uuid.toString()
                    && it.pos == pos
                    && it.newState == state
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun logPlaceDequeue(
        level: Level,
        player: Player,
        pos: BlockPos,
        state: BlockState,
    ) {
        transaction {
            SchemaUtils.create(BlockStateModification)

            BlockStateModification.batchInsert(logQueue) {
                this[BlockStateModification.performedAt] = it.performedAt
                this[BlockStateModification.oldState] = it.oldState
                this[BlockStateModification.newState] = it.newState
                this[BlockStateModification.playerUUID] = it.playerUUID
                this[BlockStateModification.playerName] = it.playerName
                this[BlockStateModification.posX] = it.pos.x
                this[BlockStateModification.posY] = it.pos.y
                this[BlockStateModification.posZ] = it.pos.z
                this[BlockStateModification.actionType] = it.actionType
            }
        }
        logQueue.clear()
    }

    private fun inspectPlace(
        level: Level,
        player: Player,
        pos: BlockPos,
        state: BlockState
    ): Boolean {
        inspect(player as ServerPlayer, pos)
        return player.inspectMode
    }

    private fun inspectBreak(
        level: Level,
        player: Player,
        pos: BlockPos,
        state: BlockState,
        blockEntity: BlockEntity?,
    ): Boolean {
        inspect(player as ServerPlayer, pos)
        return player.inspectMode
    }

    // Always on server so casting to ServerPlayer is OK
    private fun inspect(player: ServerPlayer, pos: BlockPos) {
        if (player.inspectMode) {
            val action = transaction {
                SchemaUtils.create(BlockStateModification)

                BlockStateModification.selectAll()
                    .where(
                        BlockStateModification.posX eq pos.x and (
                                BlockStateModification.posY eq pos.y
                                ) and (
                                BlockStateModification.posZ eq pos.z
                                )
                    )
                    .orderBy(BlockStateModification.performedAt to SortOrder.DESC)
                    .limit(1)
                    .toList()
            }.firstOrNull()

            if (action == null) {
                player.sendSystemMessage(Component.literal("No known actions."))
            } else {
                player.sendSystemMessage(Component.literal(
                    "${
                        action[BlockStateModification.performedAt].date
                    } ${
                        action[BlockStateModification.performedAt].time
                    } | ${
                        action[BlockStateModification.playerName]
                    }: [${action[BlockStateModification.posX]}, ${
                        action[BlockStateModification.posY]
                    }, ${action[BlockStateModification.posZ]}], ${
                        action[BlockStateModification.oldState].block.name.string
                    } -> ${
                        action[BlockStateModification.newState].block.name.string
                    }"
                ))
            }

        }
    }
}