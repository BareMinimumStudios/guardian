package com.bareminimumstudios.guardian.command

import com.bareminimumstudios.guardian.domain.*

/** Personal presentation filter. Never changes capture or saved history. */
data class SpyFilter(val users:Set<String> = emptySet(),val excludedUsers:Set<String> = emptySet(),
    val roots:Set<String> = emptySet(),val excludedRoots:Set<String> = emptySet()) {
    fun matches(row:PlayerActivity):Boolean {
        val names=listOfNotNull(row.actor.lastKnownName,row.actor.uuid.toString(),row.recipientName,row.recipientUuid?.toString()).map { it.lowercase() }
        val root=row.text.orEmpty().removePrefix("/").substringBefore(' ').substringAfter(':').lowercase()
        return (users.isEmpty() || names.any { it in users }) && names.none { it in excludedUsers } &&
            (roots.isEmpty() || root in roots) && root !in excludedRoots
    }
    fun description(commands:Boolean):String {
        val people=if(users.isEmpty())"all players" else "players "+users.joinToString(", ")
        val excluded=if(excludedUsers.isEmpty())"" else "; excluding "+excludedUsers.joinToString(", ")
        val commandText=if(!commands)"" else "; "+(if(roots.isEmpty())"all command roots" else "commands "+roots.joinToString(", "))+(if(excludedRoots.isEmpty())"" else "; hiding "+excludedRoots.joinToString(", "))
        return people+excluded+commandText
    }
    companion object {
        fun parse(raw:String,commands:Boolean):SpyFilter {
            var result=SpyFilter();val seen=mutableSetOf<String>()
            for(token in raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val key=token.substringBefore(':').lowercase();val value=token.substringAfter(':',"")
                require(key in setOf("u","c") && (commands || key!="c") && seen.add(key)) { "Use u:Alex,!Bob and, for commands, c:say,!help. Specify each once." }
                val parts=value.split(',').map { it.lowercase() }
                require(parts.size in 1..32 && parts.all { it.removePrefix("!").let { value->value.length<=64 && value.matches(if(key=="c")Regex("[a-z0-9_.-]+(?::[a-z0-9_.-]+)?") else Regex("[a-z0-9_.-]+")) } }) { "Invalid spy filter. Use comma-separated names/UUIDs or command roots; ! excludes." }
                val yes=parts.filterNot { it.startsWith('!') }.map { if(key=="c")it.substringAfter(':') else it }.toSet()
                val no=parts.filter { it.startsWith('!') }.map { it.drop(1).let { x->if(key=="c")x.substringAfter(':') else x } }.toSet()
                result=if(key=="u")result.copy(users=yes,excludedUsers=no) else result.copy(roots=yes,excludedRoots=no)
            }
            return result
        }
    }
}
