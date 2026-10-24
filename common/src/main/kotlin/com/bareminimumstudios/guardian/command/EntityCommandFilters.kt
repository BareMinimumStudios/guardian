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
                        require(!seenAction) { "Use one action filter with death, rename, tame, breed or frame" }; seenAction=true
                        kinds=token.substringAfter(':').split(',').flatMap { if(it.equals("frame",true)) listOf(EntityAuditKind.FRAME_PLACE,EntityAuditKind.FRAME_REMOVE,EntityAuditKind.FRAME_CHANGE) else listOf(when(it.lowercase()) {
                            "death" -> EntityAuditKind.DEATH
                            "rename" -> EntityAuditKind.NAME_CHANGE
                            "tame" -> EntityAuditKind.TAME
                            "breed" -> EntityAuditKind.BREED
                            "frame-place" -> EntityAuditKind.FRAME_PLACE
                            "frame-remove" -> EntityAuditKind.FRAME_REMOVE
                            "frame-change" -> EntityAuditKind.FRAME_CHANGE
                            else -> error("Entity actions are death, rename, tame, breed and frame")
                        }) }.toSet()
                    }
                    "id" -> { require(target==null) { "Use one entity id filter" }; target=UUID.fromString(token.substringAfter(':')) }
                    else -> common.add(token)
                }
            }
            EntityCommandFilters(BlockCommandFilterParser.parse(common.joinToString(" ")).getOrThrow(),kinds,target)
        }
    }
}
