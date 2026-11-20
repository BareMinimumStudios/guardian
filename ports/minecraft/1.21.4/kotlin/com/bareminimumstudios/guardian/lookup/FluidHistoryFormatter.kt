package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.domain.*
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent

object FluidHistoryFormatter {
    fun line(row: FluidAuditEvent,now: Long=System.currentTimeMillis()): Component {
        val player=((row.actor as? ActorIdentity.Player)?.lastKnownName ?: "environment/unknown").filter { !it.isISOControl() && it!='§' && Character.getType(it)!=Character.FORMAT.toInt() }
        val detail=when(row.kind) {
            FluidAuditKind.PLACE -> "placed ${row.after.fluidId} with a bucket"
            FluidAuditKind.REMOVE -> "collected ${row.before.fluidId} with a bucket"
            FluidAuditKind.FLOW -> "fluid changed ${row.before.fluidId} -> ${row.after.fluidId}"
            FluidAuditKind.FORM -> "fluid formed ${row.after.blockId}"
        }
        val p=row.position
        val hover="Dimension: ${row.dimension}\nTime: ${java.time.Instant.ofEpochMilli(row.timestampEpochMillis)}\nBefore: ${row.before.fluidId} (source=${row.before.source}, amount=${row.before.amount ?: "unknown"}) in ${row.before.blockId}\nAfter: ${row.after.fluidId} (source=${row.after.source}, amount=${row.after.amount ?: "unknown"}) in ${row.after.blockId}"
        return Component.literal("${((now-row.timestampEpochMillis).coerceAtLeast(0)/1000)}s ago: $player $detail @ ${p.x}, ${p.y}, ${p.z}")
            .withStyle(ChatFormatting.AQUA).withStyle { it.withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT,Component.literal(hover))) }
    }
}
