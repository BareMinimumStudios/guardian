package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.ItemStackSnapshot
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.world.item.ItemStack

/** One list handoff. This primitive alone never grants equipment restore or save authority. */
internal class MinecraftDetachedEquipmentList private constructor(
    private val backing:EquipmentListBinding,private val storage:MinecraftOwnedItemStorage,
    private val registries:HolderLookup.Provider
) : MinecraftInventoryHandoff {
    private val thread=Thread.currentThread()
    private var released=false
    override fun isCurrent():Boolean {
        check(Thread.currentThread()===thread)
        return !released && backing.read()===storage.view
    }
    override fun write(index:Int,expected:ItemStackSnapshot,replacement:ItemStackSnapshot,setter:(ItemStack)->Unit) {
        check(isCurrent());storage.throughSetter(index,expected,replacement,setter);check(isCurrent())
    }
    override fun release() {
        check(isCurrent())
        val expected=storage.snapshot()
        val list=NonNullList.withSize(expected.size,ItemStack.EMPTY)
        expected.forEachIndexed { index,item -> list[index]=MinecraftItemSnapshotter.restore(item,registries) }
        backing.install(list)
        val capture=MinecraftItemSnapshotter.CaptureBatch(registries)
        check(backing.read().map(capture::capture)==expected) { "Equipment changed during handback" }
        released=true;storage.retire()
    }
    companion object {
        fun capture(backing:EquipmentListBinding,registries:HolderLookup.Provider):MinecraftDetachedEquipmentList {
            val original=backing.read()
            val storage=MinecraftOwnedItemStorage.capture(original,registries)
            check(backing.read()===original)
            val result=MinecraftDetachedEquipmentList(backing,storage,registries)
            backing.install(storage.view);check(result.isCurrent())
            return result
        }
    }
}
