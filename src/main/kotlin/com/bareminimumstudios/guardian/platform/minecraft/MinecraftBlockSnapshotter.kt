package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.BlockStateSnapshot
import com.bareminimumstudios.guardian.domain.ResourceId
import net.minecraft.block.BlockState
import net.minecraft.block.entity.BlockEntity
import net.minecraft.registry.Registries
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World

/**
 * Converts live Minecraft state into immutable, worker-safe Guardian domain data.
 * This must run on the logical server thread; no Minecraft object escapes the returned snapshot.
 */
object MinecraftBlockSnapshotter {

    fun snapshot(world: World, pos: BlockPos): BlockStateSnapshot =
        snapshot(world, world.getBlockState(pos), world.getBlockEntity(pos))

    fun snapshot(
        world: World,
        state: BlockState,
        blockEntity: BlockEntity?
    ): BlockStateSnapshot {
        val blockIdentifier = checkNotNull(Registries.BLOCK.getId(state.block)) {
            "Cannot snapshot unregistered block ${state.block}"
        }

        val properties = LinkedHashMap<String, String>(state.entries.size)
        for ((property, value) in state.entries) {
            properties[property.name] = BlockStatePropertySerializer.valueName(property, value)
        }

        val blockEntityPayload = blockEntity?.let {
            val nbt = it.createNbtWithIdentifyingData(world.registryManager)
            MinecraftNbtPayloadCodec.encode(nbt)
        }

        return BlockStateSnapshot(
            blockId = ResourceId.parse(blockIdentifier.toString()),
            properties = properties,
            blockEntityData = blockEntityPayload
        )
    }
}
