package com.bareminimumstudios.guardian.logging.container

import com.bareminimumstudios.guardian.domain.*

/** A transfer must conserve each immutable item identity across its two inventories. */
class HopperTransferCorrelation @JvmOverloads constructor(
    before: InventorySnapshot,
    source: Set<ItemSlotOwner>,
    destination: Set<ItemSlotOwner>,
    actor:ActorIdentity.System=ActorIdentity.System("minecraft:hopper")
) {
    private val blockContexts=(source+destination).filterIsInstance<ItemSlotOwner.BlockContainer>()
    private val source = source.toSet()
    private val destination = destination.toSet()
    private val correlation = ContainerTransactionCorrelation(actor, 0, ContainerAction.HOPPER_TRANSFER, before)
    init {
        require((source+destination).all { it is ItemSlotOwner.BlockContainer || it is ItemSlotOwner.EntityContainer })
        require(source.isNotEmpty() && destination.isNotEmpty() && source.intersect(destination).isEmpty())
        require(before.slots.keys.all { it.owner in source || it.owner in destination })
    }
    private data class ItemIdentity(val id: ResourceId, val data: BinaryPayload?)

    fun finish(after: InventorySnapshot): ContainerTransactionSnapshot? {
        val value = correlation.finish(after, true) ?: return null
        val removed = mutableMapOf<ItemIdentity, Long>()
        val inserted = mutableMapOf<ItemIdentity, Long>()
        for (change in value.changes) {
            val delta = if (change.address.owner in source) removed else inserted
            add(delta, change.before, -1); add(delta, change.after, 1)
        }
        removed.entries.removeIf { it.value == 0L }; inserted.entries.removeIf { it.value == 0L }
        require(removed.isNotEmpty() && removed.values.all { it < 0 } && inserted.values.all { it > 0 } &&
            removed.mapValues { -it.value } == inserted) { "Observed hopper inventories do not describe a balanced transfer" }
        return ContainerTransactionSnapshot(value.transactionId,value.timestampEpochMillis,value.actor,value.menuId,value.action,value.changes,containers=(blockContexts+value.changes.mapNotNull { it.address.owner.auditLocation() }).distinct())
    }

    private fun add(delta: MutableMap<ItemIdentity, Long>, item: ItemStackSnapshot, sign: Int) {
        if (item.isEmpty) return
        val key = ItemIdentity(requireNotNull(item.itemId), item.itemData)
        delta[key] = (delta[key] ?: 0L) + sign.toLong() * item.count
    }
}
