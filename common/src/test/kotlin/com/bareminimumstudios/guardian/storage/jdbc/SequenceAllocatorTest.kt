package com.bareminimumstudios.guardian.storage.jdbc

import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.test.assertEquals

class SequenceAllocatorTest {
    @Test
    fun `reserving ranges advances sequence once and never overlaps`() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use {
                it.executeUpdate(
                    "CREATE TABLE ex_sequences (sequence_name VARCHAR PRIMARY KEY, next_value BIGINT NOT NULL)"
                )
            }

            val allocator = SequenceAllocator()
            assertEquals(1L..3L, allocator.reserve(connection, "block", 3))
            assertEquals(4L..5L, allocator.reserve(connection, "block", 2))
            assertEquals(6L, allocator.next(connection, "block"))
            assertEquals(7L..8L, allocator.reserve(connection, "block", 2))
        }
    }
}
