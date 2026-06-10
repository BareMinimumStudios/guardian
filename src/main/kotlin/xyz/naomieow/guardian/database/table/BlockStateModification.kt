package xyz.naomieow.guardian.database.table

import com.google.gson.JsonParser
import kotlinx.datetime.LocalDateTime
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime
import org.jetbrains.exposed.v1.json.json
import xyz.naomieow.guardian.ext.blockStateFromJson
import xyz.naomieow.guardian.ext.toJson


data class BlockStateModificationData(
    val performedAt: LocalDateTime,
    val oldState: BlockState,
    val newState: BlockState,
    val playerUUID: String,
    val playerName: String,
    val pos: BlockPos,
)

object BlockStateModification : Table("blockstate_modification") {
    val id = integer(name = "id").autoIncrement()
    val performedAt = datetime("performed_at").defaultExpression(CurrentDateTime)
    val oldState = json("old_state", { state ->
        state.toJson().toString()
    }, { json ->
        blockStateFromJson(JsonParser.parseString(json))!!
    })
    val newState = json("new_state", { state ->
        state.toJson().toString()
    }, { json ->
        blockStateFromJson(JsonParser.parseString(json))!!
    })
    val playerUUID = text("player_uuid").nullable()
    val playerName = text("player_name").nullable()
    val posX = integer(name = "pos_x")
    val posY = integer(name = "pos_y")
    val posZ = integer(name = "pos_z")
    val reverted = bool("reverted").default(false)

    override val primaryKey = PrimaryKey(id)
}