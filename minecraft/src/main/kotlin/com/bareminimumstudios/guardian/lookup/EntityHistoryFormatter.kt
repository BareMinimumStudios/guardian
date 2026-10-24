package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.domain.*
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.ClickEvent

object EntityHistoryFormatter {
    fun line(row: EntityAuditEvent, now: Long = System.currentTimeMillis()): Component {
        fun safe(value: String) = value.filter { !it.isISOControl() && it != '§' && Character.getType(it) != Character.FORMAT.toInt() }
        val actor = when(val a=row.actor) {
            is ActorIdentity.Player -> a.lastKnownName ?: "player"
            is ActorIdentity.Entity -> a.entityType.toString()
            is ActorIdentity.System -> a.source
            ActorIdentity.Unknown -> "unknown actor"
        }
        val entity = row.targetType.toString() + (row.beforeName?.let { " \"${safe(it)}\"" } ?: "")
        val detail = if(row.kind==EntityAuditKind.DEATH) "$entity died; source: ${safe(actor)}" else
            "${safe(actor)} renamed $entity to ${row.afterName?.let { "\"${safe(it)}\"" } ?: "no custom name"}"
        val pos=row.position
        val hover="Entity: ${row.targetUuid}\nType: ${row.targetType}\nDimension: ${row.dimension}\nTime: ${java.time.Instant.ofEpochMilli(row.timestampEpochMillis)}" +
            (row.damageType?.let { "\nDamage: $it" } ?: "") + "\nClick to search this entity."
        return Component.literal("${((now-row.timestampEpochMillis).coerceAtLeast(0)/1000)}s ago: $detail @ ${pos.x}, ${pos.y}, ${pos.z}")
            .withStyle(if(row.kind==EntityAuditKind.DEATH) ChatFormatting.RED else ChatFormatting.AQUA)
            .withStyle { it.withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT,Component.literal(hover)))
                .withClickEvent(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,"/guardian entities id:${row.targetUuid}")) }
    }
}
