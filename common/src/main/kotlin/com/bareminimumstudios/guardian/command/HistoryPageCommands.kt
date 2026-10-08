package com.bareminimumstudios.guardian.command

object HistoryPageCommands {
    fun command(subcommand: String, raw: String, page: Int, oldestFirst: Boolean): String {
        require(subcommand == "lookup" || subcommand == "transactions")
        require(page in 1..10000)
        val filters = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it.substringBefore(':').lowercase() !in setOf("p", "page", "o", "order") }
        return "/guardian $subcommand " + (filters + "p:$page" + "o:${if (oldestFirst) "oldest" else "newest"}").joinToString(" ")
    }
}
