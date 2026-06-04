package xyz.naomieow.guardian.action

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.minecraft.world.level.block.Blocks
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import xyz.naomieow.guardian.Guardian
import xyz.naomieow.guardian.database.table.BlockStateModification
import xyz.naomieow.guardian.event.BlockEvent
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

object BlockStateAction : Action {
    @OptIn(ExperimentalTime::class)
    override fun register() {
        BlockEvent.Break.AFTER.subscribe { level, player, pos, state, blockEntity ->
            if (Guardian.actionConfig.player.breakBlock) {
                transaction {
                    SchemaUtils.create(BlockStateModification)

                    BlockStateModification.insert {
                        it[performedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                        it[oldState] = state
                        it[newState] = Blocks.AIR.defaultBlockState()
                        it[playerUUID] = player?.uuid?.toString()
                        it[playerName] = player?.name?.string
                        it[posX] = pos.x
                        it[posY] = pos.y
                        it[posZ] = pos.z
                    }
                }
            }
            false
        }
    }
}