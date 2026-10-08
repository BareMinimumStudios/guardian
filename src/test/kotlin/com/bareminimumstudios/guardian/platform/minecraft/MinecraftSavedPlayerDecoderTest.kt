package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.*
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.UUID
import kotlin.test.*

class MinecraftSavedPlayerDecoderTest {
    companion object { init { SharedConstants.tryDetectVersion();Bootstrap.bootStrap() };private val registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY) }
    private val owner=ItemSlotOwner.PlayerInventory(UUID.randomUUID())
    private fun address(index: Int)=ItemSlotAddress(owner,index)
    private fun item(slot: Int,stack: ItemStack=ItemStack(Items.COAL,5))=(stack.save(registries) as CompoundTag).apply { putByte("Slot",slot.toByte()) }
    private fun tag(vararg items: CompoundTag)=CompoundTag().apply { putInt("DataVersion",SharedConstants.getCurrentVersion().dataVersion.version);putUUID("UUID",owner.playerId);put("Inventory",ListTag().apply { items.forEach { add(it) } }) }
    private fun decode(tag: CompoundTag,wanted: Set<ItemSlotAddress> = setOf(address(0)))=MinecraftSavedPlayerDecoder.decode(tag,owner,wanted,registries)
    @Test fun mapsMainArmorAndUnsignedOffhandSlotsToLiveLogicalIndices() {
        val snapshot=decode(tag(item(0),item(35),item(100),item(103),item(150)),(0..40).map(::address).toSet())
        for(index in listOf(0,35,36,39,40)) assertEquals(5,snapshot.slots[address(index)]?.count)
        assertEquals(ItemStackSnapshot.EMPTY,snapshot.slots[address(37)]);assertEquals(41,snapshot.slots.size)
    }
    @Test fun preservesCountsComponentsAndInputNbt() {
        val stack=ItemStack(Items.COAL,3).apply { set(DataComponents.CUSTOM_NAME,Component.literal("Stored player coal")) };val tag=tag(item(0,stack));val before=tag.copy()
        assertEquals(MinecraftItemSnapshotter.capture(stack,registries),decode(tag).slots[address(0)]);assertEquals(before,tag)
    }
    @Test fun refusesWrongMissingUuidAndIncompatibleDataVersion() {
        for(change in listOf<(CompoundTag)->Unit>({ it.remove("UUID") },{ it.putUUID("UUID",UUID.randomUUID()) },{ it.putInt("DataVersion",1) },{ it.remove("DataVersion") })) assertFails { decode(tag().apply(change)) }
    }
    @Test fun requiresInventoryListAndRejectsDuplicateOrMalformedSlots() {
        assertFails { decode(tag().apply { remove("Inventory") }) };assertFails { decode(tag().apply { putString("Inventory","invalid") }) }
        assertFails { decode(tag(item(0),item(0))) };assertFails { decode(tag(item(0).apply { putInt("Slot",0) })) }
        assertFails { decode(tag(item(36))) };assertFails { decode(tag(item(151))) }
    }
    @Test fun rejectsUnknownItemsAndMalformedCodecFields() {
        assertFails { decode(tag(item(0).apply { putString("id","example:missing") })) }
        assertFails { decode(tag(item(0).apply { remove("id") })) }
    }
    @Test fun rejectsWrongOwnerEmptySelectionAndUnsupportedLogicalIndices() {
        assertFails { decode(tag(),emptySet()) };assertFails { decode(tag(),setOf(address(41))) }
        assertFails { decode(tag(),setOf(ItemSlotAddress(ItemSlotOwner.PlayerInventory(UUID.randomUUID()),0))) }
        assertFails { decode(tag(),setOf(ItemSlotAddress(ItemSlotOwner.Cursor(owner.playerId),0))) }
    }
    @Test fun emptyInventoryAndUnselectedItemsRemainEmptyWithoutExtraSlots() {
        assertEquals(ItemStackSnapshot.EMPTY,decode(tag()).slots[address(0)])
        assertEquals(mapOf(address(0) to ItemStackSnapshot.EMPTY),decode(tag(item(150))).slots)
    }
}
