package com.bareminimumstudios.guardian.storage.jdbc
import java.sql.Statement
/** Reconstruct the older block layout before replaying its migration path. */
internal object LegacyMovementSchema {
    fun stripPrivateActivity(statement:Statement) {
        statement.execute("DROP TABLE IF EXISTS ex_checkpoint_restore_claim")
        statement.execute("DROP TABLE IF EXISTS ex_checkpoint_restore_link")
        statement.execute("DROP TABLE IF EXISTS ex_inventory_checkpoint")
        statement.execute("DROP TABLE IF EXISTS ex_item_restore_claim")
        statement.execute("DROP TABLE IF EXISTS ex_item_restore_link")

        val journalColumns=try { statement.connection.createStatement().use { probe -> probe.executeQuery("SELECT * FROM ex_item_rollback WHERE 1=0").use { rs -> (1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it).lowercase() }.toSet() } } } catch(_:java.sql.SQLException) { emptySet() }
        if("purpose" in journalColumns) {
            statement.executeUpdate("DROP INDEX ex_item_rollback_phase_idx")
            statement.executeUpdate("ALTER TABLE ex_item_rollback DROP COLUMN purpose")
            statement.executeUpdate("CREATE INDEX ex_item_rollback_phase_idx ON ex_item_rollback(phase,created_at)")
        }
        val columns=try { statement.connection.createStatement().use { probe->probe.executeQuery("SELECT * FROM ex_player_activity WHERE 1=0").use { rs->(1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it).lowercase() }.toSet() } } } catch(_:java.sql.SQLException) { return }
        if("recipient_uuid" in columns) {
            val indexes=GuardianSchema.migrations.flatMap { it.statements }.filter { it.startsWith("CREATE INDEX") && " ON ex_player_activity(" in it }
            indexes.forEach { statement.executeUpdate("DROP INDEX "+it.substringAfter("INDEX ").substringBefore(' ')) }
            statement.executeUpdate("ALTER TABLE ex_player_activity DROP COLUMN recipient_uuid")
            statement.executeUpdate("ALTER TABLE ex_player_activity DROP COLUMN recipient_name")
            indexes.filterNot { "ex_activity_recipient_idx" in it }.forEach(statement::executeUpdate)
        }
    }
    fun strip(statement:Statement) {
        stripPrivateActivity(statement)
        statement.executeUpdate("DROP TABLE ex_container_entity")
        val indexes=GuardianSchema.migrations.flatMap{it.statements}.filter{it.startsWith("CREATE INDEX") && " ON ex_block(" in it}
        for(sql in indexes)statement.executeUpdate("DROP INDEX "+sql.substringAfter("INDEX ").removePrefix("IF NOT EXISTS ").substringBefore(' '))
        statement.executeUpdate("ALTER TABLE ex_block DROP COLUMN movement_uuid");statement.executeUpdate("ALTER TABLE ex_block DROP COLUMN movement_phase")
        indexes.filterNot{"ex_block_movement_idx" in it}.forEach(statement::executeUpdate)
    }
}
