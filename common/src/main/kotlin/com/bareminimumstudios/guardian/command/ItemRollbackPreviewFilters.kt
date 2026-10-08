package com.bareminimumstudios.guardian.command

/** Same user/time/region syntax as history, without pagination or block-action filters. */
object ItemRollbackPreviewFilters {
    private val keys=setOf("u","user","t","time","r","radius","x","y","z")
    fun parse(raw: String): Result<BlockCommandFilter> = runCatching {
        val parsed=BlockCommandFilterParser.parse(raw).getOrThrow()
        require(parsed.lookbackMillis != null) { "Item rollback preview requires t:<time>." }
        require(raw.trim().split(Regex("\\s+")).all { it.substringBefore(':').lowercase() in keys }) {
            "Item rollback preview accepts only u:, t:, r: and complete x:/y:/z: coordinates."
        }
        parsed
    }
    fun suggestions(token: String, players: Collection<String>): List<String> =
        FilterSuggestions.values(token,players,true).filter { it.substringBefore(':').lowercase() in keys }
}
