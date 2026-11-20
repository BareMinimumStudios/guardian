package com.bareminimumstudios.guardian.command

/** Inventory states use player/time pagination, not a block radius or an item-action filter. */
object CheckpointCommandFilters {
    private val keys=setOf("u","user","t","time","l","limit","p","page")
    fun parse(raw:String):Result<BlockCommandFilter> = runCatching {
        val value=BlockCommandFilterParser.parse(raw).getOrThrow()
        require(!value.search.extended && tokens(raw).all { it in keys }) { "Use optional u:, t:, l: and p: for inventory checkpoints." }
        require((value.limit ?: 10) in 1..50 && (value.page ?: 1) in 1..2000) { "Checkpoint pages support l:1–50 and p:1–2000." }
        value
    }
    fun capture(raw:String):Result<String> = runCatching {
        val value=BlockCommandFilterParser.parse(raw).getOrThrow()
        require(value.actorName!=null && !value.search.extended && tokens(raw).size==1 && tokens(raw).all { it=="u" || it=="user" }) {
            "Use u:<online player> for a manual inventory checkpoint."
        }
        value.actorName
    }
    fun suggestions(token:String,players:Collection<String>):List<String> =
        FilterSuggestions.values(token,players,false,extended=false).filter { it.substringBefore(':').lowercase() in keys }
    private fun tokens(raw:String)=if(raw.isBlank())emptyList() else raw.trim().split(Regex("\\s+")).map { it.substringBefore(':').lowercase() }
}
