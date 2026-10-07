package com.bareminimumstudios.guardian.domain

/** Defensive immutable wrapper for serialized NBT/component payloads. */
class BinaryPayload private constructor(bytes: ByteArray) {
    private val data: ByteArray = bytes.copyOf()

    val size: Int
        get() = data.size

    fun copyBytes(): ByteArray = data.copyOf()

    override fun equals(other: Any?): Boolean =
        other is BinaryPayload && data.contentEquals(other.data)

    override fun hashCode(): Int = data.contentHashCode()

    override fun toString(): String = "BinaryPayload(size=$size)"

    companion object {
        fun of(bytes: ByteArray): BinaryPayload = BinaryPayload(bytes)
    }
}
