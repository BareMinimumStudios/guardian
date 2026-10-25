package com.bareminimumstudios.guardian.command

/** Suggestions replace only the current key:value token, preserving earlier filters. */
object FilterSuggestions {
    /** Recovery owns its ordering and record cap; only block/user scope is completed here. */
    fun recoveryValues(token: String, players: Collection<String>, resources: Collection<String>): List<String> =
        values(token,players,resources=resources).filter {
            it.substringBefore(':').lowercase() !in setOf("l","limit","p","page","o","order","m","movement") &&
                (it.substringBefore(':').lowercase() !in setOf("c","cause") || it.substringAfter(':') in setOf("player","worldedit"))
        }.take(100)

    fun values(token: String, players: Collection<String>, items: Boolean = false, resources: Collection<String> = emptyList(), extended: Boolean = true): List<String> {
        val key = token.substringBefore(':').lowercase()
        val prefix = token.substringBefore(':') + ":"
        if (':' !in token) return (listOf("u:", "user:", "t:", "time:", "r:", "radius:", "l:", "limit:", "p:", "page:", "o:", "order:", "x:", "y:", "z:") + (if (extended) listOf("i:", "include:", "e:", "exclude:") else emptyList()) + if (items) emptyList() else listOf("a:", "action:", "c:", "cause:", "m:", "movement:")).filter { it.startsWith(token, true) }
        val value = token.substringAfter(':')
        if (extended && key in setOf("u", "user", "i", "include", "e", "exclude")) {
            val head = if (',' in value) value.substringBeforeLast(',') + "," else ""
            val tail = value.substringAfterLast(',')
            val negate = key in setOf("u", "user") && tail.startsWith('!')
            val fragment = tail.removePrefix("!")
            val used = head.split(',').map { it.removePrefix("!").lowercase() }.toSet()
            val candidates = if (key in setOf("u", "user")) players else resources
            return candidates.distinct().sorted().filter { it.lowercase() !in used &&
                (it.startsWith(fragment, true) || (key !in setOf("u", "user") && ':' !in fragment && it.removePrefix("minecraft:").startsWith(fragment, true))) }
                .map { prefix + head + (if (negate) "!" else "") + it }
        }
        val values = when (key) {
            "u", "user" -> players.sorted()
            "t", "time" -> if (value.matches(Regex("\\d+"))) listOf("s", "m", "h", "d", "w").map { value + it } else listOf("30m", "1h", "12h", "1d", "7d")
            "r", "radius" -> listOf("0", "5", "10", "25", "100", "#worldedit")
            "l", "limit" -> listOf("10", "20", "50", "100", "500")
            "o", "order" -> listOf("newest", "oldest")
            "p", "page" -> listOf("1", "2", "3", "4", "5")
            "c", "cause" -> if(items) emptyList() else listOf("fire","explosion","growth","decay","piston","gravity","sign","player","worldedit")
            "a", "action" -> if (items) emptyList() else listOf("place", "break", "change", "block")
            else -> emptyList()
        }
        return values.filter { it.startsWith(value, true) }.map { prefix + it }
    }
}
