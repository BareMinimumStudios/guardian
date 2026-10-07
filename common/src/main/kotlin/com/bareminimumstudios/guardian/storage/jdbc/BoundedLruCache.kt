package com.bareminimumstudios.guardian.storage.jdbc

/** Not thread-safe; JDBC backend serialization provides the lock. */
internal class BoundedLruCache<K, V>(
    private val maxEntries: Int
) {
    init {
        require(maxEntries > 0)
    }

    private val map = object : LinkedHashMap<K, V>(maxEntries + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
    }

    operator fun get(key: K): V? = map[key]
    operator fun set(key: K, value: V) {
        map[key] = value
    }

    fun clear() = map.clear()
}
