package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.*
import net.minecraft.world.item.*
import kotlin.test.*

class MinecraftEquipmentSavedNbtTest {
    companion object { init { MinecraftTestBootstrap.initialize() }; private val registries=MinecraftTestBootstrap.registries }
    private val item=MinecraftItemSnapshotter.capture(ItemStack(Items.COAL,9),registries)
    private val empty=ItemStackSnapshot.EMPTY
    private fun address(provider:String,slot:String)=EquipmentSlotAddress(provider,slot)
    private fun saved(slot:Int?=null,byte:Boolean=false)=MinecraftItemSnapshotter.decodeTag(item.itemData!!.copyBytes()).apply {
        putInt("count",9)
        if(slot!=null) { if(byte)putByte("Slot",slot.toByte()) else putInt("Slot",slot) }
    }
    private fun image(extra:Map<EquipmentSlotAddress,ItemStackSnapshot>)=buildMap {
        for(index in 0..26)put(address("minecraft","ender_chest/$index"),empty)
        putAll(extra)
    }
    private fun layout(image:Map<EquipmentSlotAddress,ItemStackSnapshot>)=PlayerStorageLayout(41,image.keys.map { it.provider }.toSet(),image.keys)
    private fun player()=CompoundTag().apply { put("EnderItems",ListTag());putString("Unrelated","keep player data") }

    @Test fun `positional trinkets and sparse ender items round trip without discarding metadata`() {
        val slots=image(mapOf(address("trinkets","hand/ring/0") to item,address("trinkets","hand/ring/1") to empty)).toMutableMap()
        slots[address("minecraft","ender_chest/3")]=item
        val tag=player()
        (tag.get("EnderItems") as ListTag).add(saved(3,true))
        val ring=CompoundTag().apply {
            put("Items",ListTag().apply { add(saved());add(CompoundTag()) })
            put("Metadata",CompoundTag().apply { putInt("keep_modifier_data",17) })
        }
        tag.put("cardinal_components",CompoundTag().apply { put("trinkets:trinkets",CompoundTag().apply { put("hand",CompoundTag().apply { put("ring",ring) }) }) })
        val before=tag.copy();val topology=layout(slots)
        MinecraftEquipmentSavedNbt.verify(tag,null,EquipmentSavePlatform.FABRIC,topology,slots)
        val target=slots.mapValues { empty }
        val after=MinecraftEquipmentSavedNbt.repair(tag,null,EquipmentSavePlatform.FABRIC,topology,slots,target)
        assertEquals(before,tag)
        val again=MinecraftEquipmentSavedNbt.repair(after.player,null,EquipmentSavePlatform.FABRIC,topology,slots,target)
        assertEquals(after.player,again.player)
        val undo=MinecraftEquipmentSavedNbt.repair(after.player,null,EquipmentSavePlatform.FABRIC,topology,target,slots)
        assertEquals(before,undo.player)
    }

    @Test fun `separate cosmetic image retains skin flags when empty and during undo`() {
        val slots=image((0..3).associate { address("cosmeticarmorreworked","armor/$it") to if(it==0)item else empty })
        val tag=player();val cosmetic=CompoundTag().apply {
            putInt("Size",4);putString("Hidden","keep visibility data")
            put("Items",ListTag().apply { add(saved(0).apply { putBoolean("isSkinArmor",true) }) })
        }
        val before=cosmetic.copy();val topology=layout(slots);val target=slots.mapValues { empty }
        val after=MinecraftEquipmentSavedNbt.repair(tag,cosmetic,EquipmentSavePlatform.NEOFORGE,topology,slots,target)
        assertEquals(before,cosmetic)
        val metadata=((after.cosmeticArmor!!.get("Items") as ListTag)[0] as CompoundTag)
        assertTrue(metadata.get("isSkinArmor") is ByteTag);assertNull(metadata.get("id"))
        val undo=MinecraftEquipmentSavedNbt.repair(after.player,after.cosmeticArmor,EquipmentSavePlatform.NEOFORGE,topology,target,slots)
        assertEquals(before,undo.cosmeticArmor)
        assertFailsWith<IllegalArgumentException> { MinecraftEquipmentSavedNbt.verify(tag,null,EquipmentSavePlatform.NEOFORGE,topology,slots) }
    }

    @Test fun `conflicting counts and unselected slots refuse without modifying the document`() {
        val slots=image(emptyMap()).toMutableMap();slots[address("minecraft","ender_chest/3")]=item
        val tag=player();val raw=saved(3,true).apply { putInt("count",8) }
        (tag.get("EnderItems") as ListTag).add(raw)
        val before=tag.copy()
        assertFailsWith<IllegalArgumentException> { MinecraftEquipmentSavedNbt.repair(tag,null,EquipmentSavePlatform.FABRIC,layout(slots),slots,slots.mapValues { empty }) }
        assertEquals(before,tag)
        raw.putInt("count",9);(tag.get("EnderItems") as ListTag).add(saved(5,true))
        assertFailsWith<IllegalArgumentException> { MinecraftEquipmentSavedNbt.repair(tag,null,EquipmentSavePlatform.FABRIC,layout(slots),slots,slots) }
    }

    @Test fun `duplicates unknown item fields and missing recorded slots refuse`() {
        val slots=image(emptyMap()).toMutableMap();slots[address("minecraft","ender_chest/3")]=item
        val tag=player();(tag.get("EnderItems") as ListTag).add(saved(3,true));(tag.get("EnderItems") as ListTag).add(saved(3,true))
        assertFailsWith<IllegalArgumentException> { MinecraftEquipmentSavedNbt.verify(tag,null,EquipmentSavePlatform.FABRIC,layout(slots),slots) }
        (tag.get("EnderItems") as ListTag).clear();(tag.get("EnderItems") as ListTag).add(saved(3,true).apply { putString("unknown_item_field","must not discard") })
        assertFailsWith<IllegalArgumentException> { MinecraftEquipmentSavedNbt.verify(tag,null,EquipmentSavePlatform.FABRIC,layout(slots),slots) }
        assertFailsWith<IllegalArgumentException> { MinecraftEquipmentSavedNbt.verify(player(),null,EquipmentSavePlatform.FABRIC,layout(slots),slots.filterKeys { it.slot!="ender_chest/0" }) }
    }
    @Test fun `foreign loader equipment roots refuse instead of selecting a second copy`() {
        val fixtures=listOf(
            "accessories" to CompoundTag().apply { put("accessories:inventory_holder",CompoundTag()) },
            "travelersbackpack" to CompoundTag().apply { put("travelersbackpack:travelers_backpack",CompoundTag()) },
            "backpacked" to CompoundTag().apply {
                put("framework:data_holder",ListTag().apply { add(CompoundTag().apply {
                    putString("ClassKey","minecraft:player");putString("DataKey","backpacked:backpacks")
                    put("Value",ListTag().apply { add(saved()) })
                }) })
            }
        )
        for((provider,foreign) in fixtures) {
            val slots=image(emptyMap());val topology=PlayerStorageLayout(41,setOf("minecraft",provider),slots.keys)
            val tag=player().apply { put("neoforge:attachments",foreign) };val before=tag.copy()
            val error=assertFailsWith<IllegalArgumentException> {
                MinecraftEquipmentSavedNbt.repair(tag,null,EquipmentSavePlatform.FABRIC,topology,slots,slots)
            }
            assertTrue(error.message.orEmpty().contains("Ambiguous"),error.message)
            assertEquals(before,tag)
        }
        val slots=image(emptyMap());val topology=PlayerStorageLayout(41,setOf("minecraft","accessories"),slots.keys)
        val neo=player().apply { put("fabric:attachments",CompoundTag().apply { put("accessories:inventory_holder",CompoundTag()) }) }
        assertFailsWith<IllegalArgumentException> { MinecraftEquipmentSavedNbt.verify(neo,null,EquipmentSavePlatform.NEOFORGE,topology,slots) }
    }

}
