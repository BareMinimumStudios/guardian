package com.bareminimumstudios.guardian.storage.jdbc
import java.sql.Statement
/** Reconstruct the older block layout before replaying its migration path. */
internal object LegacyMovementSchema {
    fun strip(statement:Statement) {
        val indexes=GuardianSchema.migrations.flatMap{it.statements}.filter{it.startsWith("CREATE INDEX") && " ON ex_block(" in it}
        for(sql in indexes)statement.executeUpdate("DROP INDEX "+sql.substringAfter("INDEX ").removePrefix("IF NOT EXISTS ").substringBefore(' '))
        statement.executeUpdate("ALTER TABLE ex_block DROP COLUMN movement_uuid");statement.executeUpdate("ALTER TABLE ex_block DROP COLUMN movement_phase")
        indexes.filterNot{"ex_block_movement_idx" in it}.forEach(statement::executeUpdate)
    }
}
