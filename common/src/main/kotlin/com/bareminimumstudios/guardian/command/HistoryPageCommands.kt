package com.bareminimumstudios.guardian.command

object HistoryPageCommands {
    fun command(subcommand: String, raw: String, page: Int, oldestFirst: Boolean): String {
        require(subcommand in setOf("lookup", "transactions", "chat", "sessions", "commands", "whispers", "entities", "fluids"))
        require(page in 1..10000)
        val filters = Regex("(?:[^\\s\"]+|\"[^\"]*\")+").findAll(raw.trim()).map { it.value }.toList().filter { it.isNotEmpty() && it.substringBefore(':').lowercase() !in setOf("p", "page", "o", "order") }
        return "/guardian $subcommand " + (filters + "p:$page" + "o:${if (oldestFirst) "oldest" else "newest"}").joinToString(" ")
    }
}
