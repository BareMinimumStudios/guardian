package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import java.util.Collections

/** Bounded read-only observation; a result is never authorization to mutate or clear a journal. */
class ItemRecoveryCheck(val record: ItemRollbackRecord) {
    val owners: List<ItemSlotOwner>
    private val wanted: Set<ItemSlotAddress>
    private val seen=mutableSetOf<ItemSlotOwner>()
    private var retainedBytes=0L
    private val live=linkedMapOf<ItemSlotAddress,ItemStackSnapshot>()
    init {
        require(record.entries.size in 1..50 && record.entries.sumOf { it.changes.size } <= 2048)
        require(record.entries.map { it.transactionId }.distinct().size==record.entries.size)
        val changes=record.entries.flatMap { it.changes }
        require(changes.sumOf { (it.before.itemData?.size ?: 0).toLong()+(it.after.itemData?.size ?: 0) } <= 16L*1024*1024)
        wanted=changes.map { it.address }.toSet()
        owners=Collections.unmodifiableList(ArrayList(wanted.map { it.owner }.distinct()))
        require(owners.size in 1..32 && owners.all { it is ItemSlotOwner.BlockContainer || it is ItemSlotOwner.PlayerInventory })
        val virtual=linkedMapOf<ItemSlotAddress,ItemStackSnapshot>()
        changes.forEach { virtual.putIfAbsent(it.address,it.after) }
        record.entries.forEach { entry ->
            require(entry.changes.isNotEmpty() && entry.changes.map { it.address }.distinct().size==entry.changes.size)
            require(ContainerRollbackPlanner.conserving(entry.changes))
            require(entry.changes.all { virtual[it.address]==it.after }) { "Inconsistent recovery chain" }
            entry.changes.forEach { virtual[it.address]=it.before }
        }
    }
    fun addresses(owner: ItemSlotOwner): Set<ItemSlotAddress> { require(owner in owners);return wanted.filter { it.owner==owner }.toSet() }
    fun accept(owner: ItemSlotOwner,snapshot: InventorySnapshot?) {
        require(owner in owners && seen.add(owner))
        val selected=snapshot?.slots?.filterKeys { it in wanted && it.owner==owner } ?: return
        val bytes=selected.values.sumOf { (it.itemData?.size ?: 0).toLong() }
        if(retainedBytes+bytes>16L*1024*1024) return
        retainedBytes+=bytes
        selected.forEach { (address,item) -> live[address]=item }
    }
    fun observe(changedOwners: Set<ItemSlotOwner> = emptySet()): ItemRecoveryObservation {
        check(seen.size==owners.size) { "Recovery observation is incomplete" }
        require(changedOwners.all { it in owners })
        val retained=live.filterKeys { it.owner !in changedOwners }
        return ItemRollbackRecovery.observe(record,InventorySnapshot(retained))
    }
}
