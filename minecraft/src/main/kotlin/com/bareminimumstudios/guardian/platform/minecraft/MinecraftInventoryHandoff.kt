package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.ItemStackSnapshot
import net.minecraft.world.item.ItemStack

/** Physically detached inventory storage. Journal, save and lifetime authority belong to its coordinator. */
internal interface MinecraftInventoryHandoff {
    fun isCurrent(): Boolean
    fun write(index: Int,expected: ItemStackSnapshot,replacement: ItemStackSnapshot,setter: (ItemStack) -> Unit)
    fun beginWrite() {}
    fun prepareRelease() {}
    fun release()
}
