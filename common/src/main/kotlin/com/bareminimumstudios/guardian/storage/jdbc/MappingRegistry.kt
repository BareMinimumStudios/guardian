package com.bareminimumstudios.guardian.storage.jdbc

import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

internal class MappingRegistry(
    private val table: String,
    private val valueColumn: String,
    private val sequence: String,
    private val allocator: SequenceAllocator
) {
    private val byValue = ConcurrentHashMap<String, Int>()
    private val byId = ConcurrentHashMap<Int, String>()

    fun idFor(connection: Connection, value: String): Int {
        byValue[value]?.let { return it }
        val existing = connection.prepareStatement("SELECT id FROM $table WHERE $valueColumn = ?").use { statement ->
            statement.setString(1, value)
            statement.executeQuery().use { result -> if (result.next()) result.getInt(1) else null }
        }
        if (existing != null) {
            cache(existing, value)
            return existing
        }

        val id = allocator.next(connection, sequence).toIntExact(sequence)
        connection.prepareStatement("INSERT INTO $table(id, $valueColumn) VALUES (?, ?)").use { statement ->
            statement.setInt(1, id)
            statement.setString(2, value)
            statement.executeUpdate()
        }
        cache(id, value)
        return id
    }

    fun valueFor(connection: Connection, id: Int): String {
        byId[id]?.let { return it }
        val value = connection.prepareStatement("SELECT $valueColumn FROM $table WHERE id = ?").use { statement ->
            statement.setInt(1, id)
            statement.executeQuery().use { result ->
                check(result.next()) { "Missing mapping $table id=$id" }
                result.getString(1)
            }
        }
        cache(id, value)
        return value
    }

    fun clear() {
        byValue.clear()
        byId.clear()
    }

    private fun cache(id: Int, value: String) {
        byValue[value] = id
        byId[id] = value
    }

    private fun Long.toIntExact(label: String): Int {
        require(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Mapping sequence $label exhausted integer IDs" }
        return toInt()
    }
}
