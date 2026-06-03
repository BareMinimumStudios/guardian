package xyz.naomieow.guardian.database.driver

import org.jetbrains.exposed.v1.jdbc.Database

interface Driver {
    fun connect(): Database
}