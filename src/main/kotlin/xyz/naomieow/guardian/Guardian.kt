package xyz.naomieow.guardian

import com.akuleshov7.ktoml.file.TomlFileReader
import com.akuleshov7.ktoml.file.TomlFileWriter
import kotlinx.serialization.serializer
import net.fabricmc.api.ModInitializer
import net.fabricmc.loader.api.FabricLoader
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import xyz.naomieow.guardian.config.DatabaseConfig
import xyz.naomieow.guardian.config.databaseConfigPath
import xyz.naomieow.guardian.database.driver.H2MemDriver
import xyz.naomieow.guardian.database.driver.MySQLDriver
import xyz.naomieow.guardian.database.driver.SQLiteDriver
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.time.ExperimentalTime

const val MOD_ID: String = "guardian"
const val MOD_NAME: String = "Guardian"
const val CONFIG_DIR: String = "config/$MOD_ID"

object Guardian :
    ModInitializer,
    Logger by LoggerFactory.getLogger(MOD_NAME)
{

    lateinit var db: Database
    val databaseConfig: DatabaseConfig by lazy {
        val file = TomlFileReader.decodeFromFile<DatabaseConfig>(
            serializer(),
            databaseConfigPath
        )
        file
    }

    @OptIn(ExperimentalTime::class)
    override fun onInitialize() {
        info("Go. Open that door. I want to see what the sunlight outside is like…")

        ensureConfigs()

        info("Using driver: ${databaseConfig.driver}")
        db = when (databaseConfig.driver) {
            "sqlite" -> SQLiteDriver.connect()
            "mysql" -> MySQLDriver.connect()
            "h2" -> {
                error("h2 database is not persistent. DO NOT USE in production.")
                H2MemDriver.connect()
            }
            else -> {
                warn("Unknown database driver ${databaseConfig.driver}, defaulting to sqlite.")
                SQLiteDriver.connect()
            }
        }

    }

    fun isModLoaded(id: String): Boolean {
        return FabricLoader.getInstance().isModLoaded(id)
    }

	private fun ensureConfigs() {
        Files.createDirectories(Paths.get(CONFIG_DIR))
		if (!File(databaseConfigPath).exists()) {
            TomlFileWriter().encodeToFile(
                serializer(),
                DatabaseConfig(),
                databaseConfigPath
            )
		}
	}
}
