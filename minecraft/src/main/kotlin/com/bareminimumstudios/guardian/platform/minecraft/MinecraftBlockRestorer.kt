package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.BlockPosition
import com.bareminimumstudios.guardian.domain.BlockStateSnapshot
import net.minecraft.world.level.block.Block
import net.minecraft.server.level.ServerLevel
import net.minecraft.core.BlockPos

object MinecraftBlockRestorer {
    data class RestoreResult(
        val success: Boolean,
        val reason: String? = null
    )

    fun restore(world: ServerLevel, position: BlockPosition, target: BlockStateSnapshot): RestoreResult {
        val pos = BlockPos(position.x, position.y, position.z)
        val state = runCatching { BlockStateSnapshotDecoder.decode(target) }
            .getOrElse { return RestoreResult(false, it.message ?: "Unable to decode recorded block state") }

        if (!world.setBlock(pos, state, Block.UPDATE_ALL or Block.UPDATE_KNOWN_SHAPE)) {
            return RestoreResult(false, "Minecraft rejected the recorded block state")
        }

        val payload = target.blockEntityData
        if (payload != null) {
            val blockEntity = world.getBlockEntity(pos)
                ?: return RestoreResult(false, "Recorded block entity is missing after restoration")
            val nbt = runCatching { MinecraftNbtPayloadCodec.decode(payload) }
                .getOrElse { return RestoreResult(false, it.message ?: "Unable to decode recorded block-entity data") }
            runCatching {
                blockEntity.loadWithComponents(nbt, world.registryAccess())
                blockEntity.setChanged()
                world.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS)
            }.getOrElse {
                return RestoreResult(false, it.message ?: "Unable to restore recorded block-entity data")
            }
        }

        return RestoreResult(true)
    }
}
