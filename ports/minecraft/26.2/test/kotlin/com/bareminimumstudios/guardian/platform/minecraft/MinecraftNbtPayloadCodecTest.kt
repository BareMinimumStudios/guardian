package com.bareminimumstudios.guardian.platform.minecraft

import net.minecraft.nbt.CompoundTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MinecraftNbtPayloadCodecTest {
    @Test
    fun roundTripsVersionedCompressedNbt() {
        val nbt = CompoundTag().apply {
            putString("id", "minecraft:chest")
            putInt("x", 12)
            putInt("y", 64)
            putInt("z", -4)
            putString("custom", "Guardian")
        }

        val payload = MinecraftNbtPayloadCodec.encode(nbt)
        val decoded = MinecraftNbtPayloadCodec.decode(payload)

        assertEquals("minecraft:chest", decoded.getString("id").orElseThrow())
        assertEquals(12, decoded.getInt("x").orElseThrow())
        assertEquals(64, decoded.getInt("y").orElseThrow())
        assertEquals(-4, decoded.getInt("z").orElseThrow())
        assertEquals("Guardian", decoded.getString("custom").orElseThrow())
    }

    @Test
    fun rejectsUnversionedPayloads() {
        val invalid = com.bareminimumstudios.guardian.domain.BinaryPayload.of(byteArrayOf(1, 2, 3, 4, 5, 6))
        assertFailsWith<IllegalArgumentException> {
            MinecraftNbtPayloadCodec.decode(invalid)
        }
    }
}
