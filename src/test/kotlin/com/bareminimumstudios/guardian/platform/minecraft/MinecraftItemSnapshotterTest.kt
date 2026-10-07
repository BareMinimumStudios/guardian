package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import kotlin.test.*

class MinecraftItemSnapshotterTest {
    companion object {
        init { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap() }
        private val registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
    }
    @Test fun preservesNameDamageAndExplicitDefaultRemoval() {
        val stack = ItemStack(Items.DIAMOND_SWORD, 1)
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Guardian test"))
        stack.set(DataComponents.DAMAGE, 7)
        stack.remove(DataComponents.ATTRIBUTE_MODIFIERS)
        val captured = MinecraftItemSnapshotter.capture(stack, registries)
        stack.set(DataComponents.DAMAGE, 20)
        stack.remove(DataComponents.CUSTOM_NAME)
        val restored = MinecraftItemSnapshotter.restore(captured, registries)
        assertEquals("Guardian test", restored.get(DataComponents.CUSTOM_NAME)?.string)
        assertEquals(7, restored.get(DataComponents.DAMAGE))
        assertNull(restored.get(DataComponents.ATTRIBUTE_MODIFIERS))
    }
    @Test fun countDoesNotChangeComponentPayload() {
        val stack = ItemStack(Items.DIAMOND, 8)
        val first = MinecraftItemSnapshotter.capture(stack, registries)
        stack.count = 3
        val second = MinecraftItemSnapshotter.capture(stack, registries)
        assertEquals(first.itemData, second.itemData)
        assertEquals(8, MinecraftItemSnapshotter.restore(first, registries).count)
    }
    @Test fun customDataOrderingDoesNotCreateFalseChanges() {
        val first = ItemStack(Items.STONE)
        val second = ItemStack(Items.STONE)
        first.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().apply { putString("one", "1"); putString("two", "2") }))
        second.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().apply { putString("two", "2"); putString("one", "1") }))
        assertEquals(MinecraftItemSnapshotter.capture(first, registries), MinecraftItemSnapshotter.capture(second, registries))
    }
    @Test fun emptyStackIsCanonical() {
        assertSame(ItemStackSnapshot.EMPTY, MinecraftItemSnapshotter.capture(ItemStack.EMPTY, registries))
        assertTrue(MinecraftItemSnapshotter.restore(ItemStackSnapshot.EMPTY, registries).isEmpty)
    }
    @Test fun transientComponentsAreRejectedInsteadOfSilentlyLost() {
        val component = net.minecraft.core.component.DataComponentType.builder<String>()
            .networkSynchronized(net.minecraft.network.codec.StreamCodec.unit("test")).build()
        val stack = ItemStack(Items.STONE); stack.set(component, "value")
        assertFailsWith<IllegalArgumentException> { MinecraftItemSnapshotter.capture(stack, registries) }
    }
    @Test fun corruptVersionAndMismatchedIdentityAreRejected() {
        val captured = MinecraftItemSnapshotter.capture(ItemStack(Items.STONE), registries)
        val bytes = captured.itemData!!.copyBytes(); bytes[4] = 99
        assertFailsWith<IllegalArgumentException> { MinecraftItemSnapshotter.restore(captured.copy(itemData = BinaryPayload.of(bytes)), registries) }
        assertFailsWith<IllegalArgumentException> { MinecraftItemSnapshotter.restore(captured.copy(itemId = ResourceId.parse("minecraft:diamond")), registries) }
    }
}
