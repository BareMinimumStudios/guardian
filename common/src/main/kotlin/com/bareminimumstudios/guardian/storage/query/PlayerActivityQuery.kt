package com.bareminimumstudios.guardian.storage.query

import com.bareminimumstudios.guardian.domain.PlayerActivityKind
import java.util.UUID

data class PlayerActivityQuery(
    val kinds: Set<PlayerActivityKind>, val playerUuid: UUID? = null, val playerName: String? = null,
    val afterEpochMillis: Long? = null, val limit: Int = 20, val offset: Int = 0, val oldestFirst: Boolean = false
) {
    init { require(kinds.isNotEmpty()); require(limit in 1..500); require(offset in 0..5_000_000) }
}
