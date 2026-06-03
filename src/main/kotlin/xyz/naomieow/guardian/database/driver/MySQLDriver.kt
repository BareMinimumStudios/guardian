package xyz.naomieow.guardian.database.driver

import org.jetbrains.exposed.v1.jdbc.Database
import xyz.naomieow.guardian.Guardian

object MySQLDriver : Driver {
    override fun connect(): Database {
        val config = Guardian.databaseConfig.mysql
        val host = config.host
        val port = config.port
        val database = config.database
        val user = config.user
        val password = config.password
        return Database.connect(
            url = "jdbc:mysql://$host:$port/$database",
            driver = "com.mysql.cj.jdbc.Driver",
            user = user,
            password = password,
        )
    }
}