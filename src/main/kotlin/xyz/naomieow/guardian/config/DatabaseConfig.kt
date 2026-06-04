package xyz.naomieow.guardian.config

import com.akuleshov7.ktoml.file.TomlFileReader
import com.akuleshov7.ktoml.file.TomlFileWriter
import kotlinx.serialization.Serializable
import xyz.naomieow.guardian.CONFIG_DIR
import java.io.File

const val databaseConfigPath = "$CONFIG_DIR/database.toml"

@Serializable
data class DatabaseConfig(
    val driver: String = "sqlite",
    val sqlite: SQLiteConfig = SQLiteConfig(),
    val mysql: MySQLConfig = MySQLConfig(),
) {
    companion object {
        fun load(): DatabaseConfig {
            return TomlFileReader.decodeFromFile(
                kotlinx.serialization.serializer(),
                databaseConfigPath
            )
        }

        fun ensure() {
            if (!File(databaseConfigPath).exists()) {
                TomlFileWriter().encodeToFile(
                    serializer(),
                    DatabaseConfig(),
                    databaseConfigPath
                )
            }
        }
    }
}

@Serializable
data class MySQLConfig(
    val host: String = "localhost",
    val port: Int = 5432,
    val database: String = "guardian",
    val user: String = "guardian",
    val password: String = "",
)


@Serializable
data class SQLiteConfig(
    val file: String = "guardian.db"
)

