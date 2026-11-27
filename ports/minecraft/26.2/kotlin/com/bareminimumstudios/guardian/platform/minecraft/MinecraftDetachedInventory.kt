package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.mixin.OwnedLivingEquipmentAccessor
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityEquipment
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.PlayerEquipment
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
) : MinecraftInventoryHandoff {
    private val thread = Thread.currentThread()
    private var released = false
    private val views = storage.map { it.view }

    override fun isCurrent(): Boolean {
        check(Thread.currentThread() === thread)
        if (released) return false
        return lists(container).let { current -> current.size == views.size && current.indices.all { current[it] === views[it] } }
    }

    override fun write(index: Int, expected: ItemStackSnapshot, replacement: ItemStackSnapshot, setter: (ItemStack) -> Unit) {
        check(isCurrent())
        var offset = index
        val part = storage.firstOrNull { if (offset < it.view.size) true else { offset -= it.view.size; false } }
        require(index >= 0 && part != null)
        part.throughSetter(offset, expected, replacement, setter)
        check(isCurrent())
    }

    /** Prepare every replacement first. Never invalidate an installed view on an uncertain handoff. */
    override fun release() {
        check(isCurrent())
        val mutable = storage.map { image ->
            val snapshots = image.snapshot()
            NonNullList.withSize(snapshots.size, ItemStack.EMPTY).also { list ->
                snapshots.forEachIndexed { i, stack -> list[i] = MinecraftItemSnapshotter.restore(stack, registries) }
            }
        }
        install(container, mutable, false)
        check(lists(container).let { current -> current.indices.all { i -> if(container is Inventory && i == 1) current[i].indices.all { slot -> current[i][slot] === mutable[i][slot] } else current[i] === mutable[i] } })
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
            if (container is Inventory) check(original.map { it.size } == listOf(36,7))
            val storage = original.map { MinecraftOwnedItemStorage.capture(it, registries) }
            check(original.indices.all { i -> original[i].indices.all { slot ->
                MinecraftItemSnapshotter.capture(original[i][slot], registries) == storage[i].snapshot()[slot]
            } })
            val result = MinecraftDetachedInventory(container, storage, registries)
            install(container, storage.map { it.view }, true)
            check(result.isCurrent())
            return result
        }

        /** Equipment must be detached in both aliases; reads return fresh items and writes consume a slot permit. */
        private class OwnedEquipment(val inventory: Inventory, val view: NonNullList<ItemStack>) : PlayerEquipment(inventory.player), OwnedEquipmentSnapshot {
            override fun get(slot: EquipmentSlot): ItemStack {
                if(slot == EquipmentSlot.MAINHAND) return inventory.selectedItem
                val index = (36..42).firstOrNull { Inventory.EQUIPMENT_SLOT_MAPPING.get(it) == slot }
                return if(index == null) ItemStack.EMPTY else view[index - 36]
            }
            override fun set(slot: EquipmentSlot, item: ItemStack): ItemStack {
                if(slot == EquipmentSlot.MAINHAND) return inventory.setSelectedItem(item)
                val index = requireNotNull((36..42).firstOrNull { Inventory.EQUIPMENT_SLOT_MAPPING.get(it) == slot })
                return view.set(index - 36, item)
            }
            override fun `guardian$snapshotEquipment`(): Map<EquipmentSlot,ItemStack> =
                (36..42).associate { requireNotNull(Inventory.EQUIPMENT_SLOT_MAPPING.get(it)) to view[it - 36] }.filterValues { !it.isEmpty }
            override fun isEmpty(): Boolean = inventory.selectedItem.isEmpty && view.all { it.isEmpty }
            override fun tick(owner: Entity) { /* Enclosing gameplay is fenced while owned. */ }
            override fun setAll(equipment: EntityEquipment): Unit = denied()
            override fun dropAll(dropper: LivingEntity): Unit = denied()
            override fun clear(): Unit = denied()
            private fun denied(): Nothing = throw UnsupportedOperationException("Gameplay cannot mutate owned equipment")
        }

        private fun equipment(container: Inventory): EntityEquipment {
            val inventory = (container as OwnedPlayerInventoryAccessor).`guardian$getEquipment`()
            check((container.player as OwnedLivingEquipmentAccessor).`guardian$getEquipment`() === inventory) {
                "Player equipment aliases changed"
            }
            return inventory
        }

        private fun lists(container: Container): List<NonNullList<ItemStack>> = if (container is Inventory) {
            check(container.containerSize == 43 && container.nonEquipmentItems.size == 36)
            val equipment = equipment(container)
            val items = if(equipment is OwnedEquipment) { check(equipment.inventory === container);equipment.view } else {
                check(equipment.javaClass == PlayerEquipment::class.java) { "Unsupported player equipment implementation" }
                NonNullList.withSize(7, ItemStack.EMPTY).also { result ->
                    for(index in 36..42) result[index - 36] = equipment.get(requireNotNull(Inventory.EQUIPMENT_SLOT_MAPPING.get(index)))
                }
            }
            listOf(container.nonEquipmentItems, items)
        } else listOf((container as OwnedBlockInventoryInvoker).`guardian$getItems`())

        private fun install(container: Container, lists: List<NonNullList<ItemStack>>, owned: Boolean) {
            if (container is Inventory) {
                require(lists.map { it.size } == listOf(36,7))
                equipment(container) // Validate aliases before either is replaced.
                val replacement = if(!owned) {
                    PlayerEquipment(container.player).also { result ->
                        for(index in 36..42) result.set(requireNotNull(Inventory.EQUIPMENT_SLOT_MAPPING.get(index)), lists[1][index - 36])
                    }
                } else OwnedEquipment(container,lists[1])
                val access = container as OwnedPlayerInventoryAccessor
                access.`guardian$setItems`(lists[0])
                access.`guardian$setEquipment`(replacement)
                (container.player as OwnedLivingEquipmentAccessor).`guardian$setEquipment`(replacement)
                check(equipment(container) === replacement)
            } else (container as OwnedBlockInventoryInvoker).`guardian$setItems`(lists.single())
        }
    }
}
