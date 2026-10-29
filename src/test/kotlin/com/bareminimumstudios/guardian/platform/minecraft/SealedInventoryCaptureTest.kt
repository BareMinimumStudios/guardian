package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.mixin.CompoundContainerAccessor
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.Container
import net.minecraft.world.CompoundContainer
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BarrelBlockEntity
import kotlin.test.*

class SealedInventoryCaptureTest {
    companion object { init { SharedConstants.tryDetectVersion();Bootstrap.bootStrap() } }
    private class TrappedBarrel:BarrelBlockEntity(BlockPos.ZERO,Blocks.BARREL.defaultBlockState()) {
        var reads=0
        override fun getItem(slot:Int):ItemStack { reads++;error("Audit must never unpack sealed loot") }
    }
    private class Combined(val first:Container,val second:Container):CompoundContainer(first,second),CompoundContainerAccessor {
        override fun `guardian$first`()=first
        override fun `guardian$second`()=second
    }
    private fun sealed()=TrappedBarrel().also {
        it.lootTable=ResourceKey.create(Registries.LOOT_TABLE,ResourceLocation.parse("minecraft:chests/simple_dungeon"))
    }
    @Test fun directCaptureRefusesBeforeCallingASealedItemGetter() {
        val block=sealed();val table=block.lootTable
        assertTrue(DirectBlockItems.supported(block))
        assertTrue(MinecraftBlockContainerSnapshotter.hasPendingLoot(block))
        assertFailsWith<IllegalArgumentException> { DirectBlockItems.items(block) }
        assertEquals(0,block.reads);assertEquals(table,block.lootTable)
    }
    @Test fun eitherSealedDoubleChestHalfPreventsAllSlotReads() {
        for(first in listOf(false,true)) {
            val block=sealed()
            val both=if(first)Combined(block,SimpleContainer(27)) else Combined(SimpleContainer(27),block)
            assertTrue(MinecraftBlockContainerSnapshotter.hasPendingLoot(both))
            assertEquals(0,block.reads)
        }
    }
    @Test fun ordinaryAndUnsealedInventoriesRemainObservable() {
        val barrel=BarrelBlockEntity(BlockPos.ZERO,Blocks.BARREL.defaultBlockState())
        assertFalse(MinecraftBlockContainerSnapshotter.hasPendingLoot(barrel))
        assertEquals(27,DirectBlockItems.items(barrel).size)
        assertFalse(MinecraftBlockContainerSnapshotter.hasPendingLoot(Combined(barrel,SimpleContainer(27))))
    }
}
