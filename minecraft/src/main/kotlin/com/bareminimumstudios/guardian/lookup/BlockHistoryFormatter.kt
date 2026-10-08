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
            rows.forEach { row -> add(Component.literal(formatRow(row, nowEpochMillis))) }
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
        val change = when (s.action) {
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
