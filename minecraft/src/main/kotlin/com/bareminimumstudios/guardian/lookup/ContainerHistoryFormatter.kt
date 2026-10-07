package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.domain.*
import net.minecraft.network.chat.Component
import java.time.Instant

object ContainerHistoryFormatter {
    fun lines(transactions: List<ContainerTransactionSnapshot>): List<Component> = buildList {
        if (transactions.isEmpty()) add(Component.literal("Guardian: no container transactions found."))
        for (transaction in transactions) {
            val actor = transaction.actor.lastKnownName ?: transaction.actor.uuid.toString()
            val action = transaction.action.name.lowercase().replace('_', ' ')
            add(Component.literal("${Instant.ofEpochMilli(transaction.timestampEpochMillis)} | $actor | $action | ${transaction.transactionId}"))
            for (change in transaction.changes.take(6)) {
                val owner = when (val target = change.address.owner) {
                    is ItemSlotOwner.PlayerInventory -> "player inventory"
                    is ItemSlotOwner.Cursor -> "cursor"
                    is ItemSlotOwner.BlockContainer -> "container ${target.position.x}, ${target.position.y}, ${target.position.z}"
                }
                val before = item(change.before); val after = item(change.after)
                val detail = if (before == after) "$before (components changed)" else "$before → $after"
                add(Component.literal("  $owner slot ${change.address.index}: $detail"))
            }
            if (transaction.changes.size > 6) add(Component.literal("  ${transaction.changes.size - 6} more changed slots stored in this transaction."))
        }
    }
    private fun item(value: ItemStackSnapshot) = if (value.isEmpty) "empty" else "${value.itemId} ×${value.count}"
}
