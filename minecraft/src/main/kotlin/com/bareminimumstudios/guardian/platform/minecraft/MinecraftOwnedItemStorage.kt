package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.ItemStackSnapshot
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.world.item.ItemStack

/**
 * Detached temporary storage for the online ownership adapter. The backing image contains
 * encoded immutable snapshots, never a live ItemStack or a caller-owned component map.
 * Installing this storage and excluding enclosing gameplay operations are separate duties.
 * This class alone does not authorize rollback or make an inventory exclusive.
 */
internal class MinecraftOwnedItemStorage private constructor(
    private val registries: HolderLookup.Provider,
    initial: List<ItemStackSnapshot>
) {
    private val thread = Thread.currentThread()
    private val image = initial.toMutableList()
    private var released = false
    private data class Write(val index: Int, val stack: ItemStack, val replacement: ItemStackSnapshot)
    private var write: Write? = null

    /** Vanilla serialization may read this fixed-size view, but cannot acquire mutable backing items. */
    val view: NonNullList<ItemStack> = object : NonNullList<ItemStack>(mutableListOf(), ItemStack.EMPTY) {
        override val size: Int get() { checkCurrent(); return image.size }
        override fun get(index: Int): ItemStack {
            checkCurrent()
            return MinecraftItemSnapshotter.restore(image[index], registries)
        }
        override fun set(index: Int, element: ItemStack): ItemStack {
            checkCurrent()
            val permit = write ?: denied()
            check(index == permit.index && element === permit.stack) { "Unrelated owned inventory setter" }
            write = null
            val previous = get(index)
            check(MinecraftItemSnapshotter.capture(element, registries) == permit.replacement)
            replace(index, image[index], permit.replacement)
            return previous
        }
        override fun add(index: Int, element: ItemStack): Unit = denied()
        override fun removeAt(index: Int): ItemStack = denied()
        override fun clear(): Unit = denied()
    }

    fun snapshot(): List<ItemStackSnapshot> {
        checkCurrent()
        return image.toList()
    }

    /** Exact compare-and-replace; validate the complete replacement before changing any backing byte. */
    fun replace(index: Int, expected: ItemStackSnapshot, replacement: ItemStackSnapshot) {
        checkCurrent()
        check(image[index] == expected) { "Owned slot no longer matches the planned image" }
        val decoded = MinecraftItemSnapshotter.restore(replacement, registries)
        require(MinecraftItemSnapshotter.capture(decoded, registries) == replacement) {
            "Replacement item cannot round-trip without a component change"
        }
        val bytes = image.sumOf { (it.itemData?.size ?: 0).toLong() } -
            (image[index].itemData?.size ?: 0) + (replacement.itemData?.size ?: 0)
        require(bytes <= MAX_BYTES) { "Owned inventory exceeds its payload budget" }
        image[index] = replacement
    }

    /** One exact vanilla setter may consume this capability, before any subsequent setter callback. */
    fun throughSetter(index: Int, expected: ItemStackSnapshot, replacement: ItemStackSnapshot, setter: (ItemStack) -> Unit) {
        checkCurrent()
        check(write == null && image[index] == expected)
        val stack = MinecraftItemSnapshotter.restore(replacement, registries)
        val permit = Write(index, stack, replacement)
        write = permit
        try {
            setter(stack)
            check(write == null && image[index] == replacement) { "Native setter did not consume its owned slot write" }
        } finally { write = null }
    }

    /** Fresh mutable items for handing ownership back, with no alias to an earlier read or input list. */
    fun release(): NonNullList<ItemStack> {
        checkCurrent()
        val result = NonNullList.withSize(image.size, ItemStack.EMPTY)
        image.forEachIndexed { index, stack -> result[index] = MinecraftItemSnapshotter.restore(stack, registries) }
        released = true
        image.clear()
        return result
    }

    fun retire() { checkCurrent(); check(write == null); released = true; image.clear() }

    private fun checkCurrent() {
        check(Thread.currentThread() === thread) { "Owned item storage must use its inventory thread" }
        check(!released) { "Owned item storage has already been released" }
    }

    private fun denied(): Nothing {
        checkCurrent()
        throw UnsupportedOperationException("Gameplay cannot mutate an owned inventory view")
    }

    companion object {
        private const val MAX_BYTES = 16L * 1024 * 1024
        fun capture(items: List<ItemStack>, registries: HolderLookup.Provider): MinecraftOwnedItemStorage {
            require(items.size in 1..256) { "Unsupported owned inventory size" }
            val batch = MinecraftItemSnapshotter.CaptureBatch(registries)
            var bytes = 0L
            val image = items.map { item ->
                batch.capture(item).also { snapshot ->
                    bytes += snapshot.itemData?.size ?: 0
                    require(bytes <= MAX_BYTES) { "Owned inventory exceeds its payload budget" }
                }
            }
            return MinecraftOwnedItemStorage(registries, image)
        }
    }
}
