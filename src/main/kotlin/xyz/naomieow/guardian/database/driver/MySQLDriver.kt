package xyz.naomieow.guardian.database.driver

import org.jetbrains.exposed.v1.jdbc.Database
import xyz.naomieow.guardian.Guardian

object MySQLDriver : Driver {
    override fun connect(): Database {
        val config = Guardian.databaseConfig.mysql
        return Database.connect(
            url = "jdbc:mysql://${config.host}:${config.port}/${config.database}",
            driver = "com.mysql.cj.jdbc.Driver",
            user = config.user,
            password = config.password,
        )
    }
}