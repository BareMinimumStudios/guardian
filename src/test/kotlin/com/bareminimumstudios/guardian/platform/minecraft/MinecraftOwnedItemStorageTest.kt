package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class MinecraftOwnedItemStorageTest {
    companion object {
        init { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap() }
        private val registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
    }
    private fun named() = ItemStack(Items.DIAMOND_SWORD).apply {
        set(DataComponents.CUSTOM_NAME, Component.literal("Original"))
        set(DataComponents.DAMAGE, 7)
        remove(DataComponents.ATTRIBUTE_MODIFIERS)
    }
    private fun snapshot(stack: ItemStack) = MinecraftItemSnapshotter.capture(stack, registries)

    @Test fun retainedInputListAndStackCannotMutateTheBackingImage() {
        val stack = ItemStack(Items.COAL, 12)
        val input = mutableListOf(stack)
        val storage = MinecraftOwnedItemStorage.capture(input, registries)
        stack.count = 1
        input[0] = ItemStack.EMPTY
        input.clear()
        assertEquals(12, storage.view[0].count)
        assertEquals(1, storage.view.size)
    }
    @Test fun componentPatchesAreDetachedFromInputsAndReads() {
        val input = named()
        val expected = snapshot(input)
        val storage = MinecraftOwnedItemStorage.capture(listOf(input), registries)
        input.set(DataComponents.CUSTOM_NAME, Component.literal("Input changed"))
        val read = storage.view[0]
        read.set(DataComponents.DAMAGE, 99)
        read.set(DataComponents.ATTRIBUTE_MODIFIERS, net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY)
        read.remove(DataComponents.CUSTOM_NAME)
        assertEquals(expected, snapshot(storage.view[0]))
    }
    @Test fun iteratorAndSublistCannotExposeMutableBackingStacks() {
        val storage = MinecraftOwnedItemStorage.capture(listOf(ItemStack(Items.COAL, 9), named()), registries)
        storage.view.iterator().next().count = 1
        storage.view.subList(1,2)[0].remove(DataComponents.CUSTOM_NAME)
        assertEquals(9, storage.view[0].count)
        assertEquals("Original", storage.view[1].get(DataComponents.CUSTOM_NAME)?.string)
        assertFailsWith<UnsupportedOperationException> { storage.view.listIterator().apply { next(); set(ItemStack.EMPTY) } }
        assertFailsWith<UnsupportedOperationException> { storage.view.subList(0,1).clear() }
    }
    @Test fun ordinaryListMutationsFailRatherThanPretendingToSucceed() {
        val storage = MinecraftOwnedItemStorage.capture(listOf(ItemStack(Items.COAL, 9)), registries)
        val before = storage.snapshot()
        assertFailsWith<UnsupportedOperationException> { storage.view[0] = ItemStack.EMPTY }
        assertFailsWith<UnsupportedOperationException> { storage.view.add(ItemStack.EMPTY) }
        assertFailsWith<UnsupportedOperationException> { storage.view.removeAt(0) }
        assertFailsWith<UnsupportedOperationException> { storage.view.clear() }
        assertEquals(before, storage.snapshot())
    }
    @Test fun compareAndReplacePreservesComponentsAndRefusesStaleExpectedImages() {
        val original = snapshot(ItemStack(Items.COAL, 9))
        val storage = MinecraftOwnedItemStorage.capture(listOf(ItemStack(Items.COAL, 9)), registries)
        val replacement = snapshot(named())
        storage.replace(0, original, replacement)
        assertEquals(replacement, snapshot(storage.view[0]))
        assertFailsWith<IllegalStateException> { storage.replace(0, original, ItemStackSnapshot.EMPTY) }
        assertEquals(listOf(replacement), storage.snapshot())
    }
    @Test fun malformedReplacementRefusesBeforeChangingTheSlot() {
        val storage = MinecraftOwnedItemStorage.capture(listOf(named()), registries)
        val original = storage.snapshot().single()
        val bad = ItemStackSnapshot(ResourceId.parse("minecraft:coal"), 1, BinaryPayload.of(byteArrayOf(1)))
        assertFails { storage.replace(0, original, bad) }
        assertEquals(listOf(original), storage.snapshot())
    }
    @Test fun releasingCreatesFreshItemsAndPermanentlyRevokesTheOldView() {
        val storage = MinecraftOwnedItemStorage.capture(listOf(named()), registries)
        val expected = storage.snapshot().single()
        val retained = storage.view[0]
        val released = storage.release()
        retained.count = 0
        assertEquals(expected, snapshot(released[0]))
        assertFailsWith<IllegalStateException> { storage.view[0] }
        assertFailsWith<IllegalStateException> { storage.release() }
        assertFailsWith<IllegalStateException> { storage.replace(0, expected, ItemStackSnapshot.EMPTY) }
    }
    @Test fun offThreadAccessCannotReadWriteOrReleaseTheOwnershipImage() {
        val storage = MinecraftOwnedItemStorage.capture(listOf(named()), registries)
        val before = storage.snapshot()
        CompletableFuture.runAsync {
            assertFailsWith<IllegalStateException> { storage.view[0] }
            assertFailsWith<IllegalStateException> { storage.snapshot() }
            assertFailsWith<IllegalStateException> { storage.replace(0, before.single(), ItemStackSnapshot.EMPTY) }
            assertFailsWith<IllegalStateException> { storage.release() }
        }.join()
        assertEquals(before, storage.snapshot())
    }
    @Test fun unsupportedSizesAndTransientComponentsRefuseAtCapture() {
        assertFailsWith<IllegalArgumentException> { MinecraftOwnedItemStorage.capture(emptyList(), registries) }
        assertFailsWith<IllegalArgumentException> { MinecraftOwnedItemStorage.capture(List(257) { ItemStack.EMPTY }, registries) }
        val stack = ItemStack(Items.STONE)
        val transient = net.minecraft.core.component.DataComponentType.builder<String>()
            .networkSynchronized(net.minecraft.network.codec.StreamCodec.unit("test")).build()
        stack.set(transient, "value")
        assertFailsWith<IllegalArgumentException> { MinecraftOwnedItemStorage.capture(listOf(stack), registries) }
    }
}
