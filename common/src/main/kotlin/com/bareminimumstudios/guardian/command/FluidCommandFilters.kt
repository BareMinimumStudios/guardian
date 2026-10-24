package com.bareminimumstudios.guardian.command

import com.bareminimumstudios.guardian.domain.FluidAuditKind

data class FluidCommandFilters(val common: BlockCommandFilter, val kinds: Set<FluidAuditKind>) {
    companion object {
        fun parse(raw: String): Result<FluidCommandFilters> = runCatching {
            var kinds = FluidAuditKind.entries.toSet()
            var seenAction = false
            val common = mutableListOf<String>()
            raw.trim().split(Regex("\\s+")).filter(String::isNotEmpty).forEach { token ->
                when(token.substringBefore(':').lowercase()) {
                    "a", "action" -> {
                        require(!seenAction) { "Use one action filter with place or remove" }; seenAction=true
                        kinds=token.substringAfter(':').split(',').map { when(it.lowercase()) {
                            "place" -> FluidAuditKind.PLACE
                            "remove" -> FluidAuditKind.REMOVE
                            else -> error("Fluid actions are place and remove")
                        } }.toSet()
                    }
                    else -> common.add(token)
                }
            }
            FluidCommandFilters(BlockCommandFilterParser.parse(common.joinToString(" ")).getOrThrow(),kinds)
        }
    }
}
