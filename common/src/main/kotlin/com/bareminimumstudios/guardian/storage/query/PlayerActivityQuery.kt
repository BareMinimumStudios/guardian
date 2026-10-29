package com.bareminimumstudios.guardian.storage.query

import com.bareminimumstudios.guardian.domain.PlayerActivityKind
import java.util.UUID

data class PlayerActivityQuery(
    val kinds: Set<PlayerActivityKind>, val playerUuid: UUID? = null, val playerName: String? = null,
    val afterEpochMillis: Long? = null, val limit: Int = 20, val offset: Int = 0, val oldestFirst: Boolean = false, val search: HistorySearchFilter = HistorySearchFilter()
) {
    init { require(search.included.isEmpty() && search.excluded.isEmpty()) { "Activity searches do not accept resource filters" }; require(kinds.isNotEmpty()); require(limit in 1..500); require(offset in 0..5_000_000) }
    fun matches(row:com.bareminimumstudios.guardian.domain.PlayerActivity):Boolean {
        val participants=listOfNotNull(row.actor,row.recipientUuid?.let { com.bareminimumstudios.guardian.domain.ActorIdentity.Player(it,row.recipientName) })
        return row.kind in kinds && (afterEpochMillis==null || row.timestampEpochMillis>=afterEpochMillis) &&
            (search.players.isEmpty() || search.players.any { p->participants.any(p::matches) }) &&
            search.excludedPlayers.none { p->participants.any(p::matches) } &&
            (playerUuid==null || participants.any { it.uuid==playerUuid }) &&
            (playerName==null || participants.any { it.lastKnownName.equals(playerName,true) })
    }
}
