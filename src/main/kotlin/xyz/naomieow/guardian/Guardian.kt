package xyz.naomieow.guardian

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.loader.api.FabricLoader
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import xyz.naomieow.guardian.action.BlockStateAction
import xyz.naomieow.guardian.command.GuardCommand
import xyz.naomieow.guardian.config.ActionConfig
import xyz.naomieow.guardian.config.DatabaseConfig
import xyz.naomieow.guardian.database.driver.H2MemDriver
import xyz.naomieow.guardian.database.driver.MySQLDriver
import xyz.naomieow.guardian.database.driver.SQLiteDriver
import java.nio.file.Files
import java.nio.file.Paths

const val MOD_ID: String = "guardian"
const val MOD_NAME: String = "Guardian"
const val CONFIG_DIR: String = "config/$MOD_ID"

object Guardian :
    ModInitializer,
    Logger by LoggerFactory.getLogger(MOD_NAME) {

    init {
        Files.createDirectories(Paths.get(CONFIG_DIR))
        DatabaseConfig.ensure()
        ActionConfig.ensure()
    }

    var databaseConfig: DatabaseConfig = DatabaseConfig.load()
    var actionConfig: ActionConfig = ActionConfig.load()

    lateinit var db: Database

    override fun onInitialize() {
        info("Go. Open that door. I want to see what the sunlight outside is like…")
        info("Using driver: ${databaseConfig.driver}")
        val driver = when (databaseConfig.driver) {
            "sqlite" -> SQLiteDriver
            "mysql" -> MySQLDriver
            "h2" -> {
                error("h2 database is not persistent. DO NOT USE in production.")
                H2MemDriver
            }

            else -> {
                warn("Unknown database driver ${databaseConfig.driver}, defaulting to sqlite.")
                SQLiteDriver
            }
        }
        db = driver.connect()

        BlockStateAction.register()
        registerCommands()
    }

    fun isModLoaded(id: String): Boolean {
        return FabricLoader.getInstance().isModLoaded(id)
    }

    private fun registerCommands() {
        CommandRegistrationCallback.EVENT.register { dispatcher, context, selection ->
            dispatcher.register(GuardCommand.command)
        }
    }
}
