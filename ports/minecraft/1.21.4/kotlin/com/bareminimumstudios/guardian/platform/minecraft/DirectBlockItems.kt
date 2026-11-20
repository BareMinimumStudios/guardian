package com.bareminimumstudios.guardian.platform.minecraft

import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.HangingSignBlockEntity

/** Vanilla inventory contract plus the explicit Amendments hanging-sign serialization contract.
 * Unknown custom storage is deliberately not guessed from arbitrary NBT item-shaped compounds. */
object DirectBlockItems {
    private val hangingDisplay = object : ClassValue<Boolean>() {
        override fun computeValue(type:Class<*>):Boolean = type.methods.any { it.name=="amendments\$getExtension" && it.parameterCount==0 }
    }
    fun supported(block:BlockEntity):Boolean = block is net.minecraft.world.level.block.entity.LecternBlockEntity || block is Container || block is DisplayInventoryAccess || (block is HangingSignBlockEntity && hangingDisplay.get(block.javaClass))
    fun items(block:BlockEntity):List<ItemStack> {
        require(block !is Container || !MinecraftBlockContainerSnapshotter.hasPendingLoot(block)) { "Sealed loot inventories cannot be observed" }
        if(block is net.minecraft.world.level.block.entity.LecternBlockEntity)return listOf(block.book)
        if(block is Container) {
            require(block.containerSize in 1..256) { "Direct block inventory exceeds the slot bound" }
            return (0 until block.containerSize).map(block::getItem)
        }
        if(block is DisplayInventoryAccess) {
            val items=block.`guardian$displayItems`()
            require(items.size in 1..256) { "Display inventory exceeds the slot bound" }
            return items
        }
        require(block is HangingSignBlockEntity && hangingDisplay.get(block.javaClass))
        val tag=block.saveWithFullMetadata(requireNotNull(block.level).registryAccess())
        return listOf("front_item","back_item").map { ItemStack.parseOptional(requireNotNull(block.level).registryAccess(),tag.getCompound(it)) }
    }
}
