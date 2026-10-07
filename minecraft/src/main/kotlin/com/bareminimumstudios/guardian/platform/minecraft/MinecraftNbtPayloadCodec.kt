package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.BinaryPayload
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtAccounter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Versioned, self-identifying block-entity payload format.
 *
 * Stored bytes are:
 *   EXNBT + format byte 1 + gzip-compressed vanilla NBT
 *
 * The prefix gives future importers/restorers a reliable way to distinguish Guardian-native
 * payloads from legacy/imported metadata without adding another database column.
 */
object MinecraftNbtPayloadCodec {
    private val MAGIC = byteArrayOf('E'.code.toByte(), 'X'.code.toByte(), 'N'.code.toByte(), 'B'.code.toByte(), 'T'.code.toByte())
    private const val FORMAT_VERSION: Byte = 1
    private const val HEADER_SIZE = 6
    private const val MAX_DECODED_NBT_BYTES = 64L * 1024L * 1024L

    fun encode(nbt: CompoundTag): BinaryPayload {
        val output = ByteArrayOutputStream()
        output.write(MAGIC)
        output.write(FORMAT_VERSION.toInt())
        NbtIo.writeCompressed(nbt, output)
        return BinaryPayload.of(output.toByteArray())
    }

    fun decode(payload: BinaryPayload): CompoundTag {
        val bytes = payload.copyBytes()
        require(bytes.size >= HEADER_SIZE) { "Guardian NBT payload is truncated" }
        require(bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "Unsupported block-entity payload format (missing EXNBT header)"
        }
        require(bytes[MAGIC.size] == FORMAT_VERSION) {
            "Unsupported Guardian NBT payload version: ${bytes[MAGIC.size]}"
        }

        return ByteArrayInputStream(bytes, HEADER_SIZE, bytes.size - HEADER_SIZE).use { input ->
            NbtIo.readCompressed(input, NbtAccounter.create(MAX_DECODED_NBT_BYTES))
        }
    }
}