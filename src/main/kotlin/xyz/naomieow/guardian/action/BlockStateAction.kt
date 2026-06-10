package xyz.naomieow.guardian.action

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import xyz.naomieow.guardian.Guardian
import xyz.naomieow.guardian.database.table.BlockStateModification
import xyz.naomieow.guardian.event.PlayerBlockEvent
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

object BlockStateAction : Action {
    private var previousId = -1

    @OptIn(ExperimentalTime::class)
    override fun register() {
        PlayerBlockEvent.Break.AFTER.subscribe { level, player, pos, state, blockEntity ->
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
                    }
                }
            }
        }

        PlayerBlockEvent.Place.BEFORE.subscribe {level, player, pos, state ->
            if (Guardian.actionConfig.player.placeBlock) {
                transaction {
                    SchemaUtils.create(BlockStateModification)

                    previousId = BlockStateModification.insert {
                        it[performedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                        it[oldState] = level.getBlockState(pos)
                        it[newState] = state
                        it[playerUUID] = player.uuid.toString()
                        it[playerName] = player.name.string
                        it[posX] = pos.x
                        it[posY] = pos.y
                        it[posZ] = pos.z
                    } get BlockStateModification.id
                }
            }
            false
        }

        PlayerBlockEvent.Place.CANCELLED.subscribe { level, player, pos, state ->
            Guardian.info("Cancelled")
            if (Guardian.actionConfig.player.placeBlock && previousId != -1) {
                transaction {
                    SchemaUtils.create(BlockStateModification)

                    BlockStateModification.deleteWhere {
                            BlockStateModification.posX eq pos.x and(
                                    BlockStateModification.posY eq pos.y
                            ) and(
                                    BlockStateModification.posZ eq pos.z
                            ) and(
                                    BlockStateModification.playerUUID eq player.uuid.toString()
                            ) and(
                                    // TODO: this smells and will have race conditions, fine for now, remove before v1
                                    BlockStateModification.id eq previousId
                            )
                        }
                }
                previousId = -1
            }
        }
    }
}