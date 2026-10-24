package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.domain.ActionType
import com.bareminimumstudios.guardian.domain.ActorIdentity
import com.bareminimumstudios.guardian.domain.ChangeCause
import com.bareminimumstudios.guardian.storage.query.BlockRollbackState
import com.bareminimumstudios.guardian.storage.query.StoredBlockChange
import net.minecraft.network.chat.Component
import java.time.Duration
import java.time.Instant

object BlockHistoryFormatter {
    fun lines(rows: List<StoredBlockChange>, nowEpochMillis: Long = System.currentTimeMillis()): List<Component> {
        if (rows.isEmpty()) return listOf(Component.literal("Guardian: no block history found."))
        return buildList {
            add(Component.literal("Guardian block history (${rows.size} result${if (rows.size == 1) "" else "s"}):"))
            rows.forEach { row ->
                val line=Component.literal(formatRow(row,nowEpochMillis))
                row.snapshot.movementId?.let { id -> line.withStyle { it.withHoverEvent(net.minecraft.network.chat.HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,Component.literal("Movement: $id\nPhase: ${row.snapshot.movementPhase}\nSearch this movement with m:$id"))).withClickEvent(net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND,"/guardian lookup m:$id")) } }
                add(line)
            }
        }
    }

    private fun formatRow(row: StoredBlockChange, nowEpochMillis: Long): String {
        val s = row.snapshot
        val actor = when (val identity = s.actor) {
            is ActorIdentity.Player -> identity.lastKnownName ?: identity.uuid.toString()
            is ActorIdentity.Entity -> identity.entityType.toString()
            is ActorIdentity.System -> identity.source
            ActorIdentity.Unknown -> "unknown"
        }
        val fireIds=setOf("minecraft:fire","minecraft:soul_fire")
        val change = if(s.movementPhase==com.bareminimumstudios.guardian.domain.BlockMovementPhase.FALL_START) "began falling: ${s.before.blockId}"
        else if(s.movementPhase==com.bareminimumstudios.guardian.domain.BlockMovementPhase.FALL_LAND) "falling block landed: ${s.after.blockId}"
        else if(s.movementPhase==com.bareminimumstudios.guardian.domain.BlockMovementPhase.PISTON) "piston changed ${s.before.blockId} -> ${s.after.blockId}"
        else if(s.cause==ChangeCause.FIRE) when {
            s.before.blockId.toString() in fireIds && s.after.blockId.toString() !in fireIds -> "extinguished ${s.before.blockId}"
            s.after.blockId.toString() in fireIds && s.action==ActionType.BLOCK_PLACE -> "ignited ${s.after.blockId}"
            s.before.blockId==s.after.blockId && s.before.properties["lit"]!=s.after.properties["lit"] -> "${if(s.after.properties["lit"]=="true") "lit" else "extinguished"} ${s.after.blockId}"
            else -> "burned ${s.before.blockId} -> ${s.after.blockId}"
        } else if(s.cause==ChangeCause.GROWTH) {
            val property=listOf("age","stage").firstOrNull { s.before.properties[it]!=s.after.properties[it] }
            if(s.before.blockId==s.after.blockId && property!=null) "grew ${s.after.blockId} ($property ${s.before.properties[property]} -> ${s.after.properties[property]})"
            else "grew ${s.before.blockId} -> ${s.after.blockId}"
        }
        else if(s.cause==ChangeCause.DECAY) "decayed ${s.before.blockId}"
        else when (s.action) {
            ActionType.BLOCK_PLACE -> "placed ${s.after.blockId}"
            ActionType.BLOCK_BREAK -> "broke ${s.before.blockId}"
            else -> when {
                s.before.properties["open"] != s.after.properties["open"] && s.after.properties["open"] != null -> "${if (s.after.properties["open"] == "true") "opened" else "closed"} ${s.after.blockId}"
                s.before.properties["powered"] != s.after.properties["powered"] && s.after.properties["powered"] != null -> "${if (s.after.properties["powered"] == "true") "activated" else "deactivated"} ${s.after.blockId}"
                else -> "changed ${s.before.blockId} -> ${s.after.blockId}"
            }
        }
        val cause = when (s.cause) {
            ChangeCause.WORLD_EDIT -> " via WorldEdit"
            ChangeCause.PLAYER -> ""
            else -> " via ${s.cause.name.lowercase()}"
        }
        val journal = when (row.rollbackState) {
            BlockRollbackState.ACTIVE -> ""
            BlockRollbackState.ROLLED_BACK -> " [rolled back]"
            BlockRollbackState.PENDING -> " [rollback pending]"
            BlockRollbackState.RESTORE_PENDING -> " [restore pending]"
        }
        return "#${row.rowId} ${age(nowEpochMillis, s.timestampEpochMillis)}: $actor $change$cause " +
            "@ ${s.position.x},${s.position.y},${s.position.z}$journal"
    }

    private fun age(now: Long, then: Long): String {
        val duration = Duration.between(Instant.ofEpochMilli(then), Instant.ofEpochMilli(now)).abs()
        val seconds = duration.seconds
        return when {
            seconds < 60 -> "${seconds}s ago"
            seconds < 3_600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3_600}h ago"
            else -> "${seconds / 86_400}d ago"
        }
    }
}
