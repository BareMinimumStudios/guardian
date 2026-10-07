package com.bareminimumstudios.guardian.storage.codec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BlockPropertiesCodecTest {
    @Test
    fun roundTripsAndCanonicalizesOrder() {
        val left = mapOf("waterlogged" to "false", "facing" to "north")
        val right = linkedMapOf("facing" to "north", "waterlogged" to "false")
        assertEquals(BlockPropertiesCodec.encode(left), BlockPropertiesCodec.encode(right))
        assertEquals(right, BlockPropertiesCodec.decode(BlockPropertiesCodec.encode(right)))
    }

    @Test
    fun supportsDelimiterCharactersWithoutEscaping() {
        val data = mapOf("mod:key" to "value:with=delimiters")
        assertEquals(data, BlockPropertiesCodec.decode(BlockPropertiesCodec.encode(data)))
    }

    @Test
    fun rejectsTruncatedInput() {
        assertFailsWith<IllegalArgumentException> { BlockPropertiesCodec.decode("5:abc") }
    }
}
