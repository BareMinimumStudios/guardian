package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import java.util.UUID

enum class ContainerPreviewReason { ELIGIBLE, UNSUPPORTED_ACTION, AMBIGUOUS_ORDER, UNSUPPORTED_OWNER, NONCONSERVING_ACTION, UNAVAILABLE_OWNER, OUTSIDE_SCOPE, STATE_MISMATCH, BLOCKED_CHAIN, NEWER_HISTORY, CHANGED_BLOCK, RESERVED_OWNER, CLAIMED_SOURCE }
data class ContainerPreviewEntry(val transactionId: UUID, val reason: ContainerPreviewReason, val changedSlots: Int)
class ContainerRollbackPreview(entries: List<ContainerPreviewEntry>) {
    val entries: List<ContainerPreviewEntry> = java.util.Collections.unmodifiableList(ArrayList(entries))
    val eligible: Int get() = entries.count { it.reason == ContainerPreviewReason.ELIGIBLE }
}

/** Pure reverse-history simulation. This is a preview, never permission to mutate live slots. */
object ContainerRollbackPlanner {
    private val actions = setOf(ContainerAction.PICKUP,ContainerAction.QUICK_MOVE,ContainerAction.SWAP,ContainerAction.QUICK_CRAFT,ContainerAction.PICKUP_ALL,ContainerAction.HOPPER_TRANSFER)
    fun supported(value: ContainerTransactionSnapshot): Boolean =
        value.changes.all { it.address.owner is ItemSlotOwner.BlockContainer || it.address.owner is ItemSlotOwner.PlayerInventory }

    fun conserving(value: ContainerTransactionSnapshot): Boolean {
        val totals = mutableMapOf<Pair<ResourceId?, BinaryPayload?>, Long>()
        for (change in value.changes) {
            if (!change.before.isEmpty) totals.merge(change.before.itemId to change.before.itemData, -change.before.count.toLong(), Long::plus)
            if (!change.after.isEmpty) totals.merge(change.after.itemId to change.after.itemData, change.after.count.toLong(), Long::plus)
        }
        return totals.values.all { it == 0L }
    }

    fun plan(
        newestFirst: List<ContainerTransactionSnapshot>,
        live: InventorySnapshot,
        unavailable: Set<ItemSlotOwner> = emptySet(),
        outsideScope: Set<ItemSlotOwner> = emptySet(),
        historyGuard: ContainerHistoryGuard = ContainerHistoryGuard()
    ): ContainerRollbackPreview {
        require(newestFirst.size <= 50) { "Item preview exceeds the transaction budget" }
        require(newestFirst.map { it.transactionId }.distinct().size == newestFirst.size) { "Duplicate preview transaction" }
        require(newestFirst.zipWithNext().all { (a,b) -> a.timestampEpochMillis >= b.timestampEpochMillis }) { "Preview must be newest first" }
        val ambiguous = newestFirst.groupBy { it.timestampEpochMillis }.values.flatMap { group ->
            val repeatedOwners = group.flatMap { it.changes.map { change -> change.address.owner }.distinct() }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            group.filter { value -> value.changes.any { it.address.owner in repeatedOwners } }.map { it.transactionId }
        }.toSet()
        val virtual = live.slots.toMutableMap()
        val blocked = mutableSetOf<ItemSlotOwner>()
        val entries = newestFirst.map { value ->
            val owners = value.changes.map { it.address.owner }.toSet()
            val reason = when {
                value.action !in actions -> ContainerPreviewReason.UNSUPPORTED_ACTION
                value.transactionId in ambiguous -> ContainerPreviewReason.AMBIGUOUS_ORDER
                !supported(value) -> ContainerPreviewReason.UNSUPPORTED_OWNER
                !conserving(value) -> ContainerPreviewReason.NONCONSERVING_ACTION
                value.transactionId in historyGuard.claimedTransactions -> ContainerPreviewReason.CLAIMED_SOURCE
                owners.any { it in historyGuard.reservedOwners } -> ContainerPreviewReason.RESERVED_OWNER
                owners.any { it in historyGuard.changedBlocks } -> ContainerPreviewReason.CHANGED_BLOCK
                owners.any { it in historyGuard.newerOwners } -> ContainerPreviewReason.NEWER_HISTORY
                owners.any { it in outsideScope } -> ContainerPreviewReason.OUTSIDE_SCOPE
                owners.any { it in unavailable } -> ContainerPreviewReason.UNAVAILABLE_OWNER
                owners.any { it in blocked } -> ContainerPreviewReason.BLOCKED_CHAIN
                value.changes.any { virtual[it.address] != it.after } -> ContainerPreviewReason.STATE_MISMATCH
                else -> ContainerPreviewReason.ELIGIBLE
            }
            if (reason == ContainerPreviewReason.ELIGIBLE) value.changes.forEach { virtual[it.address] = it.before }
            else blocked.addAll(owners)
            ContainerPreviewEntry(value.transactionId,reason,value.changes.size)
        }
        return ContainerRollbackPreview(entries.toList())
    }
}
