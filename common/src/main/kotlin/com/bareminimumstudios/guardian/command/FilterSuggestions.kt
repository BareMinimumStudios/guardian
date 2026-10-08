package com.bareminimumstudios.guardian.command

/** Suggestions replace only the current key:value token, preserving earlier filters. */
object FilterSuggestions {
    fun values(token: String, players: Collection<String>, items: Boolean = false): List<String> {
        val key = token.substringBefore(':').lowercase()
        val prefix = token.substringBefore(':') + ":"
        if (':' !in token) return (listOf("u:", "user:", "t:", "time:", "r:", "radius:", "l:", "limit:", "p:", "page:", "o:", "order:", "x:", "y:", "z:") + if (items) emptyList() else listOf("a:", "action:")).filter { it.startsWith(token, true) }
        val value = token.substringAfter(':')
        val values = when (key) {
            "u", "user" -> players.sorted()
            "t", "time" -> if (value.matches(Regex("\\d+"))) listOf("s", "m", "h", "d", "w").map { value + it } else listOf("30m", "1h", "12h", "1d", "7d")
            "r", "radius" -> listOf("0", "5", "10", "25", "100", "#worldedit")
            "l", "limit" -> listOf("10", "20", "50", "100", "500")
            "o", "order" -> listOf("newest", "oldest")
            "p", "page" -> listOf("1", "2", "3", "4", "5")
            "a", "action" -> if (items) emptyList() else listOf("place", "break", "change", "block")
            else -> emptyList()
        }
        return values.filter { it.startsWith(value, true) }.map { prefix + it }
    }
}
