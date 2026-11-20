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
import kotlin.test.*

class MinecraftSavedContainerDecoderTest {
    companion object {
        init { SharedConstants.tryDetectVersion();Bootstrap.bootStrap() }
        private val registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
    }
    private val owner=ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:overworld"),BlockPosition(-1,64,17))
    private val address=ItemSlotAddress(owner,0)
    private fun entity()=CompoundTag().apply { putString("id","minecraft:barrel");putInt("x",-1);putInt("y",64);putInt("z",17) }
    private fun chunk(entity: CompoundTag=entity())=CompoundTag().apply {
        putInt("DataVersion",SharedConstants.getCurrentVersion().dataVersion.version);putInt("xPos",-1);putInt("zPos",1);putString("Status","minecraft:full");put("block_entities",ListTag().apply { add(entity) })
    }
    private fun item(slot: Int=0,stack: ItemStack=ItemStack(Items.COAL,5))=(stack.save(registries) as CompoundTag).apply { putByte("Slot",slot.toByte()) }
    private fun decode(chunk: CompoundTag,wanted: Set<ItemSlotAddress> = setOf(address))=MinecraftSavedContainerDecoder.decode(chunk,owner,wanted,registries)
    @Test fun readsExactSavedCountsComponentsAndEmptySlotsWithoutMutatingNbt() {
        val stack=ItemStack(Items.COAL,5).apply { set(DataComponents.CUSTOM_NAME,Component.literal("Stored coal")) }
        val entity=entity().apply { put("Items",ListTag().apply { add(item(stack=stack)) }) };val chunk=chunk(entity);val before=chunk.copy();val empty=address.copy(index=1)
        val snapshot=decode(chunk,setOf(address,empty));assertEquals(MinecraftItemSnapshotter.capture(stack,registries),snapshot.slots[address]);assertEquals(ItemStackSnapshot.EMPTY,snapshot.slots[empty]);assertEquals(before,chunk)
    }
    @Test fun absentItemsMeansEmptyButMalformedItemsIsRejected() {
        assertEquals(ItemStackSnapshot.EMPTY,decode(chunk()).slots[address])
        assertFails { decode(chunk(entity().apply { putString("Items","invalid") })) }
        assertFails { decode(chunk(entity().apply { put("Items",ListTag().apply { add(StringTag.valueOf("invalid")) }) })) }
    }
    @Test fun refusesWrongVersionChunkCoordinatesStatusAndMissingOwner() {
        for(change in listOf<(CompoundTag)->Unit>({ it.putInt("DataVersion",1) },{ it.putInt("xPos",0) },{ it.putInt("zPos",0) },{ it.putString("Status","minecraft:empty") },{ it.remove("block_entities") })) {
            assertFails { decode(chunk().apply(change)) }
        }
        assertFails { decode(chunk(entity().apply { putInt("y",65) })) }
    }
    @Test fun refusesDuplicateOwnersUnsupportedLayoutsAndSealedLoot() {
        assertFails { decode(chunk().apply { getList("block_entities",Tag.TAG_COMPOUND.toInt()).add(entity()) }) }
        assertFails { decode(chunk(entity().apply { putString("id","example:custom_barrel") })) }
        assertFails { decode(chunk(entity().apply { putString("LootTable","minecraft:chests/simple_dungeon") })) }
    }
    @Test fun rejectsDuplicateOutOfRangeAndMalformedSavedSlotNumbers() {
        for(items in listOf(ListTag().apply { add(item());add(item()) },ListTag().apply { add(item(27)) },ListTag().apply { add(item().apply { putInt("Slot",0) }) })) {
            assertFails { decode(chunk(entity().apply { put("Items",items) })) }
        }
    }
    @Test fun refusesUnknownItemsAndMalformedItemCodecData() {
        for(tag in listOf(item().apply { putString("id","example:missing_item") },item().apply { remove("id") })) {
            assertFails { decode(chunk(entity().apply { put("Items",ListTag().apply { add(tag) }) })) }
        }
    }
    @Test fun checksPhysicalInventorySlotBoundsAndOwnerScope() {
        assertFails { decode(chunk(),emptySet()) };assertFails { decode(chunk(),setOf(address.copy(index=27))) }
        assertFails { decode(chunk(),setOf(ItemSlotAddress(owner.copy(position=BlockPosition(1,64,17)),0))) }
        assertFails { decode(chunk(entity().apply { putString("id","minecraft:hopper") }),setOf(address.copy(index=5))) }
        assertEquals(ItemStackSnapshot.EMPTY,decode(chunk(entity().apply { putString("id","minecraft:furnace") }),setOf(address.copy(index=2))).slots[address.copy(index=2)])
    }
    @Test fun capturesOnlyParticipatingSlotsAndSupportsEmptyVanillaChestHalf() {
        val entity=entity().apply { put("Items",ListTag().apply { add(item(3)) }) }
        assertEquals(setOf(address),decode(chunk(entity)).slots.keys);assertEquals(ItemStackSnapshot.EMPTY,decode(chunk(entity)).slots[address])
        assertEquals(ItemStackSnapshot.EMPTY,decode(chunk(entity().apply { putString("id","minecraft:chest") })).slots[address])
    }
}
