package xyz.naomieow.guardian.config

import com.akuleshov7.ktoml.file.TomlFileReader
import com.akuleshov7.ktoml.file.TomlFileWriter
import kotlinx.serialization.Serializable
import xyz.naomieow.guardian.CONFIG_DIR
import java.io.File


const val actionConfigPath = "$CONFIG_DIR/actions.toml"

@Serializable
data class ActionConfig (
    val player: PlayerActionConfig = PlayerActionConfig()
) {
    companion object {
        fun load(): ActionConfig {
            return TomlFileReader.decodeFromFile(
                kotlinx.serialization.serializer(),
                actionConfigPath
            )
        }

        fun ensure() {
            if (!File(actionConfigPath).exists()) {
                TomlFileWriter().encodeToFile(
                    serializer(),
                    ActionConfig(),
                    actionConfigPath
                )
            }
        }
    }
}
@Serializable
data class PlayerActionConfig (
    val breakBlock: Boolean = true,
    val placeBlock: Boolean = true,
)