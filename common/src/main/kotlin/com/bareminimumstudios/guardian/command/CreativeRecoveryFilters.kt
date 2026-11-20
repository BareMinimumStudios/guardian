package com.bareminimumstudios.guardian.command

/** Recovery selects one recorded player and a bounded result page; regions cannot describe deleted inventory slots. */
object CreativeRecoveryFilters {
    private val keys=setOf("u","user","t","time")
    fun parse(raw:String):Result<BlockCommandFilter> = runCatching {
        val value=BlockCommandFilterParser.parse(raw).getOrThrow()
        require(value.actorName!=null && value.lookbackMillis!=null && !value.search.extended) { "Use u:<player> t:<time> for recorded creative deletions." }
        require(raw.trim().split(Regex("\\s+")).all { it.substringBefore(':').lowercase() in keys }) { "Creative item recovery accepts only u: and t:." }
        value
    }
    fun suggestions(token:String,players:Collection<String>):List<String> =
        FilterSuggestions.values(token,players,true,extended=false).filter { it.substringBefore(':').lowercase() in keys }
}
