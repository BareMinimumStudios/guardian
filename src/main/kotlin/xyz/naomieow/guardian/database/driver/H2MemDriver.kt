package xyz.naomieow.guardian.database.driver

import org.jetbrains.exposed.v1.jdbc.Database

object H2MemDriver : Driver {
    override fun connect(): Database {
        return Database.connect(
            url = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
    }
}