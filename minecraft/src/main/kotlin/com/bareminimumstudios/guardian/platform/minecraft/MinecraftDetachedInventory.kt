package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.mixin.OwnedBlockInventoryInvoker
import com.bareminimumstudios.guardian.mixin.OwnedPlayerInventoryAccessor
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.*

/** One native backing-list handoff. Lifetime is owned by the coordinator, not by a read session. */
internal class MinecraftDetachedInventory private constructor(
    private val container: Container,
    private val storage: List<MinecraftOwnedItemStorage>,
    private val registries: HolderLookup.Provider
) {
    private val thread = Thread.currentThread()
    private var released = false
    private val views = storage.map { it.view }

    fun isCurrent(): Boolean {
        check(Thread.currentThread() === thread)
        if (released) return false
        return lists(container).let { current -> current.size == views.size && current.indices.all { current[it] === views[it] } }
    }

    fun write(index: Int, expected: ItemStackSnapshot, replacement: ItemStackSnapshot, setter: (ItemStack) -> Unit) {
        check(isCurrent())
        var offset = index
        val part = storage.firstOrNull { if (offset < it.view.size) true else { offset -= it.view.size; false } }
        require(index >= 0 && part != null)
        part.throughSetter(offset, expected, replacement, setter)
        check(isCurrent())
    }

    /** Prepare every replacement first. Never invalidate an installed view on an uncertain handoff. */
    fun release() {
        check(isCurrent())
        val mutable = storage.map { image ->
            val snapshots = image.snapshot()
            NonNullList.withSize(snapshots.size, ItemStack.EMPTY).also { list ->
                snapshots.forEachIndexed { i, stack -> list[i] = MinecraftItemSnapshotter.restore(stack, registries) }
            }
        }
        install(container, mutable)
        check(lists(container).let { current -> current.indices.all { current[it] === mutable[it] } })
        released = true
        storage.forEach { it.retire() }
        container.setChanged()
    }

    companion object {
        private val blocks: Set<Class<*>> = setOf(BarrelBlockEntity::class.java, ChestBlockEntity::class.java,
            HopperBlockEntity::class.java, DispenserBlockEntity::class.java, DropperBlockEntity::class.java,
            FurnaceBlockEntity::class.java, BlastFurnaceBlockEntity::class.java, SmokerBlockEntity::class.java)

        fun capture(container: Container, registries: HolderLookup.Provider): MinecraftDetachedInventory {
            check(container.javaClass == Inventory::class.java || container.javaClass in blocks)
            val original = lists(container)
            if (container is Inventory) check(original.map { it.size } == listOf(36,4,1))
            val storage = original.map { MinecraftOwnedItemStorage.capture(it, registries) }
            check(original.indices.all { i -> original[i].indices.all { slot ->
                MinecraftItemSnapshotter.capture(original[i][slot], registries) == storage[i].snapshot()[slot]
            } })
            val result = MinecraftDetachedInventory(container, storage, registries)
            install(container, storage.map { it.view })
            check(result.isCurrent())
            return result
        }

        private fun lists(container: Container): List<NonNullList<ItemStack>> = if (container is Inventory) {
            val access = container as OwnedPlayerInventoryAccessor
            val lists = listOf(container.items, container.armor, container.offhand)
            check(access.`guardian$getCompartments`().let { parts -> parts.size == 3 && parts.indices.all { parts[it] === lists[it] } }) {
                "Player inventory compartment aliases changed"
            }
            lists
        } else listOf((container as OwnedBlockInventoryInvoker).`guardian$getItems`())

        private fun install(container: Container, lists: List<NonNullList<ItemStack>>) {
            if (container is Inventory) {
                val access = container as OwnedPlayerInventoryAccessor
                access.`guardian$setItems`(lists[0]); access.`guardian$setArmor`(lists[1]); access.`guardian$setOffhand`(lists[2])
                access.`guardian$setCompartments`(lists.toList())
            } else (container as OwnedBlockInventoryInvoker).`guardian$setItems`(lists.single())
        }
    }
}
