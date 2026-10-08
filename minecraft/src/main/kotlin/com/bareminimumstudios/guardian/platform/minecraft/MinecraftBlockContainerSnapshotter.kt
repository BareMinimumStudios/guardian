package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.mixin.CompoundContainerAccessor
import com.bareminimumstudios.guardian.storage.codec.ContainerChangesCodec
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.CompoundContainer
import net.minecraft.world.RandomizableContainer
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.level.block.entity.BlockEntity

object MinecraftBlockContainerSnapshotter {
    private fun loaded(level: ServerLevel, position: BlockPos) = level.chunkSource.getChunkNow(position.x shr 4, position.z shr 4) != null

    fun resolve(level: ServerLevel, position: BlockPos): Container? {
        if (!loaded(level, position)) return null
        val blockEntity = level.getBlockEntity(position) as? Container ?: return null
        val state = level.getBlockState(position)
        val block = state.block
        if (block is ChestBlock) {
            if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE && !loaded(level, position.relative(ChestBlock.getConnectedDirection(state)))) return null
            return ChestBlock.getContainer(block, state, level, position, true)
        }
        return blockEntity
    }

    /** Reading a sealed loot container would generate loot; skip it without changing gameplay. */
    private fun observable(container: Container, level: ServerLevel): Boolean {
        if (container is CompoundContainer) {
            val parts = container as CompoundContainerAccessor
            return observable(parts.`guardian$first`(), level) && observable(parts.`guardian$second`(), level)
        }
        if (container !is BlockEntity || container.level !== level || container.isRemoved) return false
        if (!loaded(level, container.blockPos) || level.getBlockEntity(container.blockPos) !== container) return false
        return container !is RandomizableContainer || container.lootTable == null
    }

    fun capture(container: Container, level: ServerLevel): InventorySnapshot? {
        if (!observable(container, level)) return null
        require(container.containerSize in 1..ContainerChangesCodec.MAX_SLOTS)
        val slots = LinkedHashMap<ItemSlotAddress, ItemStackSnapshot>()
        val items = MinecraftItemSnapshotter.CaptureBatch(level.registryAccess())
        for (index in 0 until container.containerSize) {
            val address = address(container, index, level)
            require(address !in slots) { "Container aliases a logical slot" }
            slots[address] = items.capture(container.getItem(index))
        }
        return InventorySnapshot(slots)
    }

    private fun address(container: Container, index: Int, level: ServerLevel): ItemSlotAddress {
        if (container is CompoundContainer) {
            val parts = container as CompoundContainerAccessor
            val first = parts.`guardian$first`()
            return if (index < first.containerSize) address(first, index, level)
            else address(parts.`guardian$second`(), index - first.containerSize, level)
        }
        val block = container as BlockEntity
        val pos = block.blockPos
        return ItemSlotAddress(ItemSlotOwner.BlockContainer(ResourceId.parse(level.dimension().location().toString()), BlockPosition(pos.x, pos.y, pos.z)), index)
    }
}
