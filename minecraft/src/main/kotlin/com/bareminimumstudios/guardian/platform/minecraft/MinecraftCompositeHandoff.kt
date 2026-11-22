package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.ItemStackSnapshot
import net.minecraft.world.item.ItemStack
import java.util.IdentityHashMap

/** One logical player image with an explicit, alias-free route for each normal and named equipment slot. */
internal class MinecraftCompositeHandoff(slots: List<Slot>,private val afterRelease: ()->Unit = {}) : MinecraftInventoryHandoff {
    class Slot(val storage: MinecraftInventoryHandoff,val nativeIndex: Int)
    private val thread=Thread.currentThread()
    private val slots=slots.toList()
    private val parts: List<MinecraftInventoryHandoff>
    private var releasing=false
    private var released=false

    init {
        require(this.slots.size in 1..256)
        val indices=IdentityHashMap<MinecraftInventoryHandoff,MutableSet<Int>>()
        this.slots.forEach { slot ->
            require(slot.nativeIndex in 0..255)
            require(indices.getOrPut(slot.storage) { hashSetOf() }.add(slot.nativeIndex)) {
                "Composite inventory exposes a physical slot more than once"
            }
        }
        parts=this.slots.map { it.storage }.distinctBy { storage ->
            // Identity equality is required even if a future adapter defines structural equals.
            IdentityKey(storage)
        }
        check(isCurrent()) { "Composite inventory contains an expired native handoff" }
    }

    private class IdentityKey(val storage: MinecraftInventoryHandoff) {
        override fun equals(other: Any?)=other is IdentityKey && storage===other.storage
        override fun hashCode()=System.identityHashCode(storage)
    }

    override fun isCurrent(): Boolean {
        check(Thread.currentThread()===thread)
        return !released && !releasing && parts.all { it.isCurrent() }
    }

    override fun write(index: Int,expected: ItemStackSnapshot,replacement: ItemStackSnapshot,setter: (ItemStack) -> Unit) {
        check(isCurrent()) { "Composite inventory ownership changed" }
        require(index in slots.indices)
        parts.forEach { it.beginWrite() }
        check(isCurrent())
        val slot=slots[index]
        slot.storage.write(slot.nativeIndex,expected,replacement,setter)
        check(isCurrent()) { "Composite inventory changed during a native slot write" }
    }

    override fun prepareRelease() {
        check(isCurrent());parts.forEach { it.prepareRelease() };check(isCurrent())
    }

    override fun release() {
        check(isCurrent())
        // After any handback begins this object cannot resume writes, including on partial failure.
        // The caller must retain its gameplay/journal protection if a native handback throws.
        releasing=true
        parts.forEach { it.release() }
        afterRelease()
        released=true
    }
}
