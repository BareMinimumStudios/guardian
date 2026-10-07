package com.bareminimumstudios.guardian.storage.jdbc

import java.sql.Connection

/** Called only while the single Guardian writer transaction owns the JDBC connection. */
internal class SequenceAllocator {
    /**
     * Reserves a durable range before a large write transaction. Gaps are allowed after failures;
     * reusing an ID after a crash is not.
     */
    fun reserve(connection: Connection, name: String, count: Int): LongRange {
        require(count > 0) { "count must be positive" }
        check(connection.autoCommit) { "Sequence ranges must be reserved outside the data transaction" }
        val first = readCurrent(connection, name)
        if (first == null) {
            connection.prepareStatement(
                "INSERT INTO ex_sequences(sequence_name, next_value) VALUES (?, ?)"
            ).use { statement ->
                statement.setString(1, name)
                statement.setLong(2, 1L + count)
                statement.executeUpdate()
            }
            return 1L..count.toLong()
        }
        val after = Math.addExact(first, count.toLong())
        update(connection, name, after)
        return first..(after - 1L)
    }

    fun next(connection: Connection, name: String): Long {
        val current = readCurrent(connection, name)

        if (current == null) {
            connection.prepareStatement(
                "INSERT INTO ex_sequences(sequence_name, next_value) VALUES (?, ?)"
            ).use { statement ->
                statement.setString(1, name)
                statement.setLong(2, 2L)
                statement.executeUpdate()
            }
            return 1L
        }

        update(connection, name, Math.addExact(current, 1L))
        return current
    }

    private fun readCurrent(connection: Connection, name: String): Long? =
        connection.prepareStatement(
            "SELECT next_value FROM ex_sequences WHERE sequence_name = ?"
        ).use { statement ->
            statement.setString(1, name)
            statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
        }

    private fun update(connection: Connection, name: String, nextValue: Long) {
        connection.prepareStatement(
            "UPDATE ex_sequences SET next_value = ? WHERE sequence_name = ?"
        ).use { statement ->
            statement.setLong(1, nextValue)
            statement.setString(2, name)
            check(statement.executeUpdate() == 1) { "Sequence disappeared while allocating $name" }
        }
    }
}
