package xyz.naomieow.guardian.database.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object Action : Table("action") {
    val id = integer(name = "id").autoIncrement()
    val performedAt = datetime("performed_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}