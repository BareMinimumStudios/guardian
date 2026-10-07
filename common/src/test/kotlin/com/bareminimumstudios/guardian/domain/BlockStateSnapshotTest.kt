package com.bareminimumstudios.guardian.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class BlockStateSnapshotTest {
    @Test
    fun defensivelyCopiesProperties() {
        val source = linkedMapOf("facing" to "north")
        val snapshot = BlockStateSnapshot(ResourceId.parse("minecraft:chest"), source)
        source["facing"] = "south"

        assertEquals("north", snapshot.properties["facing"])
    }
}
