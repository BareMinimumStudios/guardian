package com.bareminimumstudios.guardian.command

import com.bareminimumstudios.guardian.export.ExportKind
import com.bareminimumstudios.guardian.domain.*

data class ExportFilters(val common:BlockCommandFilter,val entityKinds:Set<EntityAuditKind> = EntityAuditKind.entries.toSet(),val entityId:java.util.UUID?=null,val fluidKinds:Set<FluidAuditKind> = FluidAuditKind.entries.toSet()) {
    companion object {
        fun parse(kind:ExportKind,raw:String):Result<ExportFilters> = runCatching {
            val keys=raw.trim().split(Regex("\\s+")).map { it.substringBefore(':').lowercase() }
            require(keys.none { it in setOf("p","page") }) { "Export does not accept pages; narrow filters or use l: up to 500" }
            val result=when(kind) {
                ExportKind.ENTITIES->EntityCommandFilters.parse(raw).getOrThrow().let { ExportFilters(it.common,it.kinds,it.targetUuid) }
                ExportKind.FLUIDS->FluidCommandFilters.parse(raw).getOrThrow().let { ExportFilters(it.common,fluidKinds=it.kinds) }
                else->ExportFilters(BlockCommandFilterParser.parse(raw).getOrThrow())
            }
            require(result.common.lookbackMillis!=null) { "Export requires t:<time>, for example t:1h" }
            if(kind in setOf(ExportKind.CHAT,ExportKind.SESSIONS,ExportKind.COMMANDS,ExportKind.WHISPERS))require(keys.all { it in setOf("u","user","t","time","l","limit","o","order") }) { "Activity export accepts only u:, t:, l: and o:" }
            if(kind==ExportKind.ITEMS)require(keys.none { it in setOf("a","action","c","cause","m","movement") }) { "Item export does not accept block action/cause/movement filters" }
            require((result.common.limit ?: 100) in 1..500) { "Export limit must be 1..500" }
            result
        }
    }
}
