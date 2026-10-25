package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.domain.*

/** A bounded recorded result page, never a reconstructed complete historical inventory. */
internal class ItemTransactionView(rows: List<ContainerTransactionSnapshot>) {
    val rows = rows.toList()
    var transaction = 0
        private set
    var changePage = 0
        private set
    init {
        require(rows.size <= 50) { "Item UI supports at most 50 transactions per result page" }
        require(rows.sumOf { it.changes.size.toLong() } <= 4096) { "Too many changed slots; narrow the item UI search" }
        require(rows.sumOf { row -> row.changes.sumOf { (it.before.itemData?.size ?: 0).toLong() + (it.after.itemData?.size ?: 0) } } <= 8L*1024*1024) {
            "Item UI snapshots exceed 8 MiB; narrow the search"
        }
    }
    val current get() = rows.getOrNull(transaction)
    val changePages get() = current?.let { (it.changes.size+15)/16 } ?: 0
    fun changes() = current?.changes?.drop(changePage*16)?.take(16) ?: emptyList()
    fun transaction(delta: Int) {
        if(delta !in setOf(-1,1) || transaction+delta !in rows.indices)return
        transaction += delta; changePage=0
    }
    fun changes(delta: Int) {
        if(delta in setOf(-1,1) && changePage+delta in 0 until changePages)changePage+=delta
    }
}
