package com.bareminimumstudios.guardian.command

import com.bareminimumstudios.guardian.domain.ResourceId
import com.bareminimumstudios.guardian.storage.purge.PurgeScope

data class PurgeCommandFilters(val ageMillis: Long, val scope: PurgeScope) {
    companion object {
        fun parse(raw: String): Result<PurgeCommandFilters> = runCatching {
            val values=linkedMapOf<String,String>()
            for(token in raw.trim().split(Regex("\\s+"))) {
                val key=when(token.substringBefore(':').lowercase()) {
                    "t","time" -> "t"; "w","world" -> "w"; "i","include" -> "i"; "e","exclude" -> "e"
                    else -> error("Unsupported purge filter. Use t:, w:, i: or e:.")
                }
                require(':' in token && token.substringAfter(':').isNotEmpty()) { "Expected key:value." }
                require(values.put(key,token.substringAfter(':'))==null) { "Duplicate purge filter: $key" }
            }
            val age=values["t"]?.let { BlockCommandFilterParser.parse("t:$it").getOrThrow().lookbackMillis }
            require(age!=null) { "Purge requires one age, for example t:30d." }
            fun ids(key: String): Set<ResourceId> = values[key]?.split(',')?.also { require(it.size in 1..64) { "Use at most 64 IDs per type filter." } }?.map(ResourceId::parse)?.toSet() ?: emptySet()
            PurgeCommandFilters(age,PurgeScope(values["w"]?.let(ResourceId::parse),ids("i"),ids("e")))
        }
        fun suggestions(token: String, worlds: Collection<String>, resources: Collection<String>): List<String> {
            if(':' !in token)return listOf("t:","time:","w:","world:","i:","include:","e:","exclude:").filter { it.startsWith(token,true) }
            val key=token.substringBefore(':').lowercase();val prefix=token.substringBefore(':')+":";val value=token.substringAfter(':')
            return when(key) {
                "t","time" -> (if(value.matches(Regex("\\d+")))listOf(value+"d",value+"w")else listOf("1d","7d","30d","90d")).filter { it.startsWith(value,true) }.map { prefix+it }
                "w","world" -> worlds.distinct().sorted().filter { it.startsWith(value,true) || ':' !in value && it.removePrefix("minecraft:").startsWith(value,true) }.map { prefix+it }
                "i","include","e","exclude" -> FilterSuggestions.values(token,emptyList(),resources=resources).take(100)
                else -> emptyList()
            }
        }
    }
}
