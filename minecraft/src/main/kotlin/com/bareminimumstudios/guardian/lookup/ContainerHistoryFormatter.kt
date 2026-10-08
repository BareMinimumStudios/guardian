package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.domain.*
import net.minecraft.network.chat.Component
import net.minecraft.ChatFormatting

object ContainerHistoryFormatter {
    fun lines(transactions: List<ContainerTransactionSnapshot>, nowEpochMillis: Long = System.currentTimeMillis(), focus: ItemSlotOwner.BlockContainer? = null): List<Component> = buildList {
        if (transactions.isEmpty()) add(Component.literal("Guardian: no item transactions found."))
        else add(Component.literal("Guardian item history (${transactions.size} ${if (transactions.size == 1) "transaction" else "transactions"}):").withStyle(ChatFormatting.GOLD))
        for (transaction in transactions) {
            val actor = when (val identity = transaction.actor) {
                is ActorIdentity.Player -> identity.lastKnownName ?: identity.uuid.toString()
                is ActorIdentity.System -> if (identity.source == "minecraft:hopper") "Hopper" else identity.source
                is ActorIdentity.Entity -> identity.entityType.toString()
                ActorIdentity.Unknown -> "unknown"
            }
            if (transaction.action == ContainerAction.HOPPER_TRANSFER) {
                val routes = linkedMapOf<Pair<ResourceId?, BinaryPayload?>, MutableMap<ItemSlotOwner, Int>>()
                for (change in transaction.changes) {
                    if (!change.before.isEmpty) routes.getOrPut(change.before.itemId to change.before.itemData) { linkedMapOf() }.merge(change.address.owner, -change.before.count, Int::plus)
                    if (!change.after.isEmpty) routes.getOrPut(change.after.itemId to change.after.itemData) { linkedMapOf() }.merge(change.address.owner, change.after.count, Int::plus)
                }
                for ((identity, owners) in routes) {
                    val from = owners.filterValues { it < 0 }; val to = owners.filterValues { it > 0 }
                    if (focus != null && focus !in from && focus !in to) continue
                    fun positions(values: Map<ItemSlotOwner, Int>) = values.keys.joinToString(" + ") { target ->
                        val block = target as ItemSlotOwner.BlockContainer
                        "${block.position.x},${block.position.y},${block.position.z}"
                    }
                    val id = requireNotNull(identity.first)
                    val name = id.path.replace('_', ' ') + if (id.namespace == "minecraft") "" else " (${id.namespace})"
                    val line = Component.literal("${age(nowEpochMillis, transaction.timestampEpochMillis)}: Hopper moved ${to.values.sum()} $name ").withStyle(ChatFormatting.GREEN)
                    line.append(Component.literal("[${positions(from)} → ${positions(to)}]").withStyle { it.withColor(ChatFormatting.AQUA).withHoverEvent(net.minecraft.network.chat.HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, Component.literal("${positions(from)} → ${positions(to)}"))) })
                    add(line)
                }
                continue
            }
            val changes = transaction.changes.filter { focus == null || it.address.owner == focus }
            val blocks = changes.filter { it.address.owner is ItemSlotOwner.BlockContainer }
            val visible = if (focus != null || blocks.isNotEmpty()) blocks else changes.filter { it.address.owner is ItemSlotOwner.PlayerInventory }
            val groups = visible.groupBy { it.address.owner }
            var emitted = false
            for ((owner, rows) in groups) {
                // Count by component-aware identity, so renamed/enchantment changes never cancel each other.
                val deltas = linkedMapOf<Pair<ResourceId?, BinaryPayload?>, Int>()
                for (change in rows) {
                    if (!change.before.isEmpty) deltas.merge(change.before.itemId to change.before.itemData, -change.before.count, Int::plus)
                    if (!change.after.isEmpty) deltas.merge(change.after.itemId to change.after.itemData, change.after.count, Int::plus)
                }
                val location = when (owner) {
                    is ItemSlotOwner.BlockContainer -> "container @ ${owner.position.x}, ${owner.position.y}, ${owner.position.z}"
                    else -> "player inventory"
                }
                for ((item, count) in deltas) if (count != 0) {
                    val verb = if (count > 0) "added" else "removed"
                    val direction = if (count > 0) "to" else "from"
                    val itemId = requireNotNull(item.first)
                    val componentChange = rows.any { it.before.itemId == itemId && it.after.itemId == itemId && it.before.itemData != it.after.itemData }
                    val label = itemId.path.replace('_', ' ') + if (itemId.namespace == "minecraft") "" else " (${itemId.namespace})"
                    add(Component.literal("${age(nowEpochMillis, transaction.timestampEpochMillis)}: $actor $verb ${kotlin.math.abs(count)} $label${if (componentChange) " (components changed)" else ""} $direction $location").withStyle(if (count > 0) ChatFormatting.GREEN else ChatFormatting.RED))
                    emitted = true
                }
            }
            if (!emitted && changes.isEmpty() && focus != null) add(Component.literal("${age(nowEpochMillis, transaction.timestampEpochMillis)}: $actor changed items in the other half of this container.").withStyle(ChatFormatting.GRAY))
            else if (!emitted) add(Component.literal("${age(nowEpochMillis, transaction.timestampEpochMillis)}: $actor rearranged items${if (focus != null) " in this container" else " (${transaction.action.name.lowercase().replace('_', ' ')})"}.").withStyle(ChatFormatting.GRAY))
        }
    }
    private fun age(now: Long, then: Long): String {
        val seconds = ((now - then).coerceAtLeast(0) / 1000)
        return when { seconds < 60 -> "${seconds}s ago"; seconds < 3600 -> "${seconds / 60}m ago"; seconds < 86400 -> "${seconds / 3600}h ago"; else -> "${seconds / 86400}d ago" }
    }
}
