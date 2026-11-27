package com.bareminimumstudios.guardian.platform.minecraft

import net.minecraft.core.UUIDUtil
import net.minecraft.resources.RegistryOps
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
    companion object { init { MinecraftTestBootstrap.initialize() };private val registries=MinecraftTestBootstrap.registries }
    private val owner=ItemSlotOwner.PlayerInventory(UUID.randomUUID())
    private fun address(index: Int)=ItemSlotAddress(owner,index)
    private fun item(slot: Int,stack: ItemStack=ItemStack(Items.COAL,5))=(ItemStack.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE,registries),stack).orThrow as CompoundTag).apply { putByte("Slot",slot.toByte()) }
    private fun tag(vararg items: CompoundTag)=CompoundTag().apply { putInt("DataVersion",SharedConstants.getCurrentVersion().dataVersion.version);store("UUID",UUIDUtil.CODEC,owner.playerId);put("Inventory",ListTag().apply { items.forEach { add(it) } }) }
    private fun decode(tag: CompoundTag,wanted: Set<ItemSlotAddress> = setOf(address(0)))=MinecraftSavedPlayerDecoder.decode(tag,owner,wanted,registries)
    @Test fun mapsSeparateMainAndEquipmentStorageIncludingBodyAndSaddle() {
        val saved=tag(item(0),item(35)).apply {
            put("equipment",CompoundTag().apply {
                for(name in listOf("feet","head","offhand"))put(name,item(0).apply { remove("Slot") })
            })
        }
        val snapshot=decode(saved,(0..40).map(::address).toSet())
        for(index in listOf(0,35,36,39,40)) assertEquals(5,snapshot.slots[address(index)]?.count)
        assertEquals(ItemStackSnapshot.EMPTY,snapshot.slots[address(37)]);assertEquals(41,snapshot.slots.size)
    }
    @Test fun preservesCountsComponentsAndInputNbt() {
        val stack=ItemStack(Items.COAL,3).apply { set(DataComponents.CUSTOM_NAME,Component.literal("Stored player coal")) };val tag=tag(item(0,stack));val before=tag.copy()
        assertEquals(MinecraftItemSnapshotter.capture(stack,registries),decode(tag).slots[address(0)]);assertEquals(before,tag)
    }
    @Test fun refusesWrongMissingUuidAndIncompatibleDataVersion() {
        for(change in listOf<(CompoundTag)->Unit>({ it.remove("UUID") },{ it.store("UUID",UUIDUtil.CODEC,UUID.randomUUID()) },{ it.putInt("DataVersion",1) },{ it.remove("DataVersion") })) assertFails { decode(tag().apply(change)) }
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
        assertEquals(mapOf(address(0) to ItemStackSnapshot.EMPTY),decode(tag(item(35))).slots)
    }
    @Test fun refusesEquipmentWithoutPersistentInventoryAddress() {
        for(name in listOf("body","saddle")) assertFails {
            decode(tag().apply { put("equipment",CompoundTag().apply { put(name,item(0).apply { remove("Slot") }) }) })
        }
    }
}
