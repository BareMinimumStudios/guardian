package com.bareminimumstudios.guardian.command

/** Literal text only. Quoted phrases are retained in navigation; no regex or SQL patterns. */
data class ActivityTextFilter(val remaining:String,val text:String?) {
    companion object {
        fun parse(raw:String):ActivityTextFilter {
            val pattern=Regex("(?:^|\\s)(?:q|text):(?:\"([^\"]*)\"|(\\S+))",RegexOption.IGNORE_CASE)
            val matches=pattern.findAll(raw).toList()
            require(matches.size<=1) { "Use one q: text filter." }
            val match=matches.singleOrNull() ?: run {
                require(!Regex("(?:^|\\s)(?:q|text):",RegexOption.IGNORE_CASE).containsMatchIn(raw)) { "Text must be 2..128 characters. Example: q:\"two words\"." }
                return ActivityTextFilter(raw,null)
            }
            require(!match.value.substringAfter(':').startsWith('"') || match.value.endsWith('"')) { "Close the quoted q: phrase." }
            val text=match.groupValues[1].ifEmpty { match.groupValues[2] }
            require(text.length in 2..128 && text.none { it.isISOControl() }) { "Text must be 2..128 characters. Use q:\"two words\" for a phrase." }
            return ActivityTextFilter(raw.removeRange(match.range).trim(),text)
        }
    }
}
