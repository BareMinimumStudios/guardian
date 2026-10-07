package com.bareminimumstudios.guardian.domain

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BinaryPayloadTest {
    @Test
    fun defensivelyCopiesInputAndOutput() {
        val original = byteArrayOf(1, 2, 3)
        val payload = BinaryPayload.of(original)
        original[0] = 9

        val firstCopy = payload.copyBytes()
        assertContentEquals(byteArrayOf(1, 2, 3), firstCopy)

        firstCopy[1] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), payload.copyBytes())
        assertEquals(BinaryPayload.of(byteArrayOf(1, 2, 3)), payload)
    }
}
