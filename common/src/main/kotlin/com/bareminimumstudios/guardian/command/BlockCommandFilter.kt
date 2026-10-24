package com.bareminimumstudios.guardian.command

import com.bareminimumstudios.guardian.domain.ActionType
import com.bareminimumstudios.guardian.domain.BlockPosition

data class BlockCommandFilter(
    val actorName: String? = null,
    val lookbackMillis: Long? = null,
    val radius: Int? = null,
    val useWorldEditSelection: Boolean = false,
    val explicitPosition: BlockPosition? = null,
    val actions: Set<ActionType> = emptySet(),
    val limit: Int? = null,
    val page: Int? = null,
    val oldestFirst: Boolean = false,
    val search: com.bareminimumstudios.guardian.storage.query.HistorySearchFilter = com.bareminimumstudios.guardian.storage.query.HistorySearchFilter(),
    val causes: Set<com.bareminimumstudios.guardian.domain.ChangeCause> = emptySet()
)
