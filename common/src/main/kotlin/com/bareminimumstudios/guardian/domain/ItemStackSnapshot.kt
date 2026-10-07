package com.bareminimumstudios.guardian.domain

/** Count is separate from a versioned, count-one item/component encoding. */
data class ItemStackSnapshot(val itemId: ResourceId?, val count: Int, val itemData: BinaryPayload?) {
    init {
        require(count >= 0) { "Item count must not be negative" }
        require(if (count == 0) itemId == null && itemData == null else itemId != null && itemData != null) {
            "Empty stacks have no identity or payload; nonempty stacks require both"
        }
        require(itemData == null || itemData.size > 0) { "Item payload cannot be empty" }
    }
    val isEmpty: Boolean get() = count == 0
    companion object { val EMPTY = ItemStackSnapshot(null, 0, null) }
}
