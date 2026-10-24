package com.bareminimumstudios.guardian.command

import com.bareminimumstudios.guardian.domain.EntityAuditKind
import java.util.UUID

data class EntityCommandFilters(val common: BlockCommandFilter, val kinds: Set<EntityAuditKind>, val targetUuid: UUID?) {
    companion object {
        fun parse(raw: String): Result<EntityCommandFilters> = runCatching {
            var kinds = EntityAuditKind.entries.toSet()
            var target: UUID? = null
            var seenAction = false
            val common = mutableListOf<String>()
            raw.trim().split(Regex("\\s+")).filter(String::isNotEmpty).forEach { token ->
                when(token.substringBefore(':').lowercase()) {
                    "a", "action" -> {
                        require(!seenAction) { "Use one action filter with death, rename, tame or breed" }; seenAction=true
                        kinds=token.substringAfter(':').split(',').map { when(it.lowercase()) {
                            "death" -> EntityAuditKind.DEATH
                            "rename" -> EntityAuditKind.NAME_CHANGE
                            "tame" -> EntityAuditKind.TAME
                            "breed" -> EntityAuditKind.BREED
                            else -> error("Entity actions are death, rename, tame and breed")
                        } }.toSet()
                    }
                    "id" -> { require(target==null) { "Use one entity id filter" }; target=UUID.fromString(token.substringAfter(':')) }
                    else -> common.add(token)
                }
            }
            EntityCommandFilters(BlockCommandFilterParser.parse(common.joinToString(" ")).getOrThrow(),kinds,target)
        }
    }
}
