package xyz.naomieow.guardian.config

import kotlinx.serialization.Serializable
import xyz.naomieow.guardian.CONFIG_DIR

const val databaseConfigPath = "$CONFIG_DIR/database.toml"

@Serializable
data class DatabaseConfig(
    val driver: String = "sqlite",
    val sqlite: SQLiteConfig = SQLiteConfig(),
    val mysql: MySQLConfig = MySQLConfig(),
)

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

