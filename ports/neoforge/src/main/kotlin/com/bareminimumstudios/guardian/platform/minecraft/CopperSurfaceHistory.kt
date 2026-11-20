package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.BlockStateSnapshot
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.HoneycombItem
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.WeatheringCopper

/** Only recognized copper surface transitions; ordinary placement/break history stays separate. */
object CopperSurfaceHistory {
    fun supported(block:Block):Boolean = net.neoforged.neoforge.common.DataMapHooks.getBlockWaxed(block)!=null || net.neoforged.neoforge.common.DataMapHooks.getBlockUnwaxed(block)!=null
    fun describe(before:BlockStateSnapshot,after:BlockStateSnapshot):String? {
        val a=BuiltInRegistries.BLOCK.getOptional(Identifier.parse(before.blockId.toString())).orElse(null) ?: return null
        val b=BuiltInRegistries.BLOCK.getOptional(Identifier.parse(after.blockId.toString())).orElse(null) ?: return null
        return when {
            net.neoforged.neoforge.common.DataMapHooks.getBlockWaxed(a)===b -> "waxed "+before.blockId
            net.neoforged.neoforge.common.DataMapHooks.getBlockUnwaxed(a)===b -> "scraped wax from "+before.blockId
            supported(a) && WeatheringCopper.getPrevious(a).orElse(null)===b -> "scraped oxidation from "+before.blockId
            else -> null
        }
    }
}
