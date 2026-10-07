package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.BlockStateSnapshot
import com.bareminimumstudios.guardian.domain.ResourceId
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level

/**
 * Converts live Minecraft state into immutable, worker-safe Guardian domain data.
 * This must run on the logical server thread; no Minecraft object escapes the returned snapshot.
 */
object MinecraftBlockSnapshotter {

    fun snapshot(world: Level, pos: BlockPos): BlockStateSnapshot =
        snapshot(world, world.getBlockState(pos), world.getBlockEntity(pos))

    fun snapshot(
        world: Level,
        state: BlockState,
        blockEntity: BlockEntity?
    ): BlockStateSnapshot {
        val blockIdentifier = checkNotNull(BuiltInRegistries.BLOCK.getKey(state.block)) {
            "Cannot snapshot unregistered block ${state.block}"
        }

        val properties = LinkedHashMap<String, String>(state.values.size)
        for ((property, value) in state.values) {
            properties[property.name] = BlockStatePropertySerializer.valueName(property, value)
        }

        val blockEntityPayload = blockEntity?.let {
            val nbt = it.saveWithFullMetadata(world.registryAccess())
            MinecraftNbtPayloadCodec.encode(nbt)
        }

        return BlockStateSnapshot(
            blockId = ResourceId.parse(blockIdentifier.toString()),
            properties = properties,
            blockEntityData = blockEntityPayload
        )
    }
}
