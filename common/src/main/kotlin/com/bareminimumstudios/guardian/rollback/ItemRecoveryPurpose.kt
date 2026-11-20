package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*

/** A persisted authority boundary. Inventory recreation is never inferred from a failed transfer. */
enum class ItemRecoveryPurpose { TRANSFER_ROLLBACK, CREATIVE_DELETION }

object ItemRecoveryPolicy {
    private val transferActions = setOf(ContainerAction.PICKUP, ContainerAction.QUICK_MOVE,
        ContainerAction.SWAP, ContainerAction.QUICK_CRAFT, ContainerAction.PICKUP_ALL, ContainerAction.HOPPER_TRANSFER)

    fun transferAction(action: ContainerAction): Boolean = action in transferActions

    fun deletion(changes: List<ItemSlotChange>): Boolean {
        if(changes.isEmpty() || changes.size > 43) return false
        val owner=changes.first().address.owner as? ItemSlotOwner.PlayerInventory ?: return false
        return changes.all { it.address.owner==owner && it.address.index in 0..42 && !it.before.isEmpty &&
            it.before.itemData!=null && it.after.isEmpty } && changes.map { it.address }.distinct().size==changes.size
    }
    fun transaction(purpose: ItemRecoveryPurpose, row: ContainerTransactionSnapshot): Boolean = when(purpose) {
        ItemRecoveryPurpose.TRANSFER_ROLLBACK -> transferAction(row.action) &&
            ContainerRollbackPlanner.supported(row) && ContainerRollbackPlanner.conserving(row)
        ItemRecoveryPurpose.CREATIVE_DELETION -> row.action==ContainerAction.CREATIVE_SET && row.actor is ActorIdentity.Player &&
            deletion(row.changes) && row.changes.all { (it.address.owner as ItemSlotOwner.PlayerInventory).playerId==row.actor.uuid }
    }
    fun validate(purpose: ItemRecoveryPurpose, entries: List<ItemRollbackEntry>) {
        when(purpose) {
            ItemRecoveryPurpose.TRANSFER_ROLLBACK -> require(entries.all { ContainerRollbackPlanner.conserving(it.changes) }) { "Transfer recovery requires conserving changes" }
            ItemRecoveryPurpose.CREATIVE_DELETION -> {
                require(entries.all { deletion(it.changes) }) { "Creative recovery requires recorded item-to-empty player slots" }
                require(entries.flatMap { it.changes }.map { it.address.owner }.distinct().size==1) { "Creative recovery requires one player" }
                require(entries.flatMap { it.changes }.map { it.address }.distinct().size==entries.sumOf { it.changes.size }) { "A deleted slot may only be recovered once per plan" }
            }
        }
    }
}
