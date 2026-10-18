package com.bareminimumstudios.guardian.storage.query

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID

/** A known online UUID takes precedence over a mutable name. Offline names match recorded names. */
data class HistoryPlayer(val name: String, val uuid: UUID? = null) {
    init { require(Regex("[A-Za-z0-9_]{1,16}").matches(name)) }
    fun matches(actor: ActorIdentity): Boolean = actor is ActorIdentity.Player &&
        (if (uuid != null) actor.uuid == uuid else name.equals(actor.lastKnownName, ignoreCase = true))
}

data class HistorySearchFilter(
    val players: List<HistoryPlayer> = emptyList(), val excludedPlayers: List<HistoryPlayer> = emptyList(),
    val included: Set<ResourceId> = emptySet(), val excluded: Set<ResourceId> = emptySet()
) {
    init { require(players.size <= 32 && excludedPlayers.size <= 32 && included.size <= 64 && excluded.size <= 64) }
    val extended: Boolean get() = players.size > 1 || excludedPlayers.isNotEmpty() || included.isNotEmpty() || excluded.isNotEmpty()
    fun matchesActor(actor: ActorIdentity): Boolean = (players.isEmpty() || players.any { it.matches(actor) }) && excludedPlayers.none { it.matches(actor) }
    fun matchesResources(resources: Set<ResourceId>): Boolean = (included.isEmpty() || included.any { it in resources }) && excluded.none { it in resources }
    fun resolve(online: Map<String, UUID>): HistorySearchFilter {
        fun resolve(p: HistoryPlayer) = p.copy(uuid = online.entries.firstOrNull { it.key.equals(p.name, true) }?.value)
        return copy(players = players.map(::resolve), excludedPlayers = excludedPlayers.map(::resolve))
    }
}
