package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.ItemOwnerCoordination
import com.bareminimumstudios.guardian.rollback.ItemTransferCoordination
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.HopperBlock
import net.minecraft.world.level.block.entity.Hopper
import net.minecraft.world.level.block.entity.HopperBlockEntity
import net.minecraft.world.level.block.state.properties.ChestType

object MinecraftInventoryCoordination {
    private class Binding(val server: MinecraftServer, val owners: ItemOwnerCoordination) {
        val transfers = ItemTransferCoordination(owners)
    }
    @Volatile private var binding: Binding? = null

    fun install(server: MinecraftServer): ItemOwnerCoordination {
        check(server.isSameThread)
        check(binding == null) { "Inventory coordination is already installed" }
        return ItemOwnerCoordination().also { binding = Binding(server, it) }
    }

    fun uninstall(owners: ItemOwnerCoordination?) {
        val current = binding ?: return
        if (owners !== current.owners) return
        check(current.server.isSameThread)
        current.owners.stop()
        binding = null
    }

    @JvmStatic fun allowsPush(level: Level, hopper: HopperBlockEntity): Boolean = allows(level) {
        endpoints(it, hopper.blockPos, hopper.blockPos.relative(hopper.blockState.getValue(HopperBlock.FACING)))
    }

    @JvmStatic fun allowsPull(level: Level, hopper: Hopper): Boolean = allows(level) {
        if (hopper !is HopperBlockEntity) null else endpoints(it, hopper.blockPos, hopper.blockPos.above())
    }

    private fun allows(level: Level, resolve: (ServerLevel) -> Collection<ItemSlotOwner>?): Boolean {
        val current = binding ?: return true
        if (level !is ServerLevel || level.server !== current.server) return true
        if (!current.server.isSameThread) return false
        return current.transfers.allowsExternalTransfer { resolve(level) }
    }

    private fun endpoints(level: ServerLevel, a: BlockPos, b: BlockPos): Set<ItemSlotOwner> {
        val dimension = ResourceId.parse(level.dimension().location().toString())
        val positions = linkedSetOf(a.immutable(), b.immutable())
        for (position in listOf(a, b)) {
            if (level.chunkSource.getChunkNow(position.x shr 4, position.z shr 4) == null) continue
            val state = level.getBlockState(position)
            if (state.block is ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                positions.add(position.relative(ChestBlock.getConnectedDirection(state)))
            }
        }
        return positions.map { ItemSlotOwner.BlockContainer(dimension, BlockPosition(it.x, it.y, it.z)) }.toSet()
    }
}
