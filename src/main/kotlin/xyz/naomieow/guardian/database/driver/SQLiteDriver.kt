package xyz.naomieow.guardian.database.driver

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import xyz.naomieow.guardian.Guardian
import java.sql.Connection

object SQLiteDriver : Driver {
    override fun connect(): Database {
        val file = Guardian.databaseConfig.sqlite.file
        val db = Database.connect(
            url = "jdbc:sqlite:$file",
            driver = "org.sqlite.JDBC",
        )
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
        return db
    }
}