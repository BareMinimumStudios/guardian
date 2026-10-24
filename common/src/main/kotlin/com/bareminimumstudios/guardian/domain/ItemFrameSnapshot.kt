package com.bareminimumstudios.guardian.domain

data class ItemFrameSnapshot(val item: ItemStackSnapshot, val rotation: Int) {
    init { require(item.count in 0..1 && rotation in 0..7) }
}
