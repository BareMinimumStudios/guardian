package com.bareminimumstudios.guardian.storage.codec

/**
 * Stable, unambiguous representation for Minecraft block-state properties.
 * Entries are sorted by key and each key/value is length-prefixed, so modded values do not need escaping.
 */
object BlockPropertiesCodec {
    const val VERSION = 1

    fun encode(properties: Map<String, String>): String = buildString {
        properties.toSortedMap().forEach { (key, value) ->
            append(key.length).append(':').append(key)
            append(value.length).append(':').append(value)
        }
    }

    fun decode(encoded: String): Map<String, String> {
        if (encoded.isEmpty()) return emptyMap()
        var cursor = 0
        val result = linkedMapOf<String, String>()
        while (cursor < encoded.length) {
            val (keyLength, afterKeyLength) = readLength(encoded, cursor)
            cursor = afterKeyLength
            require(cursor + keyLength <= encoded.length) { "Truncated block-state key" }
            val key = encoded.substring(cursor, cursor + keyLength)
            cursor += keyLength

            val (valueLength, afterValueLength) = readLength(encoded, cursor)
            cursor = afterValueLength
            require(cursor + valueLength <= encoded.length) { "Truncated block-state value" }
            val value = encoded.substring(cursor, cursor + valueLength)
            cursor += valueLength
            require(key.isNotBlank()) { "Block-state property key cannot be blank" }
            require(result.put(key, value) == null) { "Duplicate block-state property key: $key" }
        }
        return result
    }

    private fun readLength(input: String, start: Int): Pair<Int, Int> {
        val colon = input.indexOf(':', start)
        require(colon > start) { "Invalid length prefix at offset $start" }
        val length = input.substring(start, colon).toIntOrNull()
            ?: throw IllegalArgumentException("Invalid length prefix at offset $start")
        require(length >= 0) { "Negative length prefix at offset $start" }
        return length to colon + 1
    }
}
