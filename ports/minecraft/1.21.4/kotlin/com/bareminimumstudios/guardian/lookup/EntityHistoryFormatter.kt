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
        val detail = when(row.kind) {
            EntityAuditKind.DEATH -> "$entity died; source: ${safe(actor)}"
            EntityAuditKind.NAME_CHANGE -> "${safe(actor)} renamed $entity to ${row.afterName?.let { "\"${safe(it)}\"" } ?: "no custom name"}"
            EntityAuditKind.TAME -> "${safe(actor)} tamed $entity"
            EntityAuditKind.BREED -> "$entity was born; breeding credited to ${safe(actor)}"
            EntityAuditKind.FRAME_PLACE -> "${safe(actor)} placed $entity"
            EntityAuditKind.FRAME_REMOVE -> "${safe(actor)} removed $entity" + (row.frameBefore?.item?.itemId?.let { " displaying $it" } ?: "")
            EntityAuditKind.FRAME_CHANGE -> {
                val before=checkNotNull(row.frameBefore);val after=checkNotNull(row.frameAfter)
                when {
                    before.item==after.item -> "${safe(actor)} rotated $entity (${before.rotation} -> ${after.rotation})"
                    before.item.isEmpty -> "${safe(actor)} put ${after.item.itemId} in $entity"
                    after.item.isEmpty -> "${safe(actor)} removed ${before.item.itemId} from $entity"
                    else -> "${safe(actor)} changed $entity display: ${before.item.itemId} -> ${after.item.itemId}"
                }
            }
        }
        val pos=row.position
        val hover="Entity: ${row.targetUuid}\nType: ${row.targetType}\nDimension: ${row.dimension}\nTime: ${java.time.Instant.ofEpochMilli(row.timestampEpochMillis)}" +
            (row.damageType?.let { "\nDamage: $it" } ?: "") +
            (row.parentUuid?.let { "\nParent: $it\nPartner: ${row.partnerUuid}" } ?: "") +
            (row.frameBefore?.let { "\nBefore: ${it.item.itemId ?: "empty"}, rotation ${it.rotation}" } ?: "") +
            (row.frameAfter?.let { "\nAfter: ${it.item.itemId ?: "empty"}, rotation ${it.rotation}" } ?: "") + "\nClick to search this entity."
        return Component.literal("${((now-row.timestampEpochMillis).coerceAtLeast(0)/1000)}s ago: $detail @ ${pos.x}, ${pos.y}, ${pos.z}")
            .withStyle(if(row.kind==EntityAuditKind.DEATH) ChatFormatting.RED else ChatFormatting.AQUA)
            .withStyle { it.withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT,Component.literal(hover)))
                .withClickEvent(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,"/guardian entities id:${row.targetUuid}")) }
    }
}
