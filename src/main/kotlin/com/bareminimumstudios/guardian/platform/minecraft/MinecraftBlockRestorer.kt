package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.BlockPosition
import com.bareminimumstudios.guardian.domain.BlockStateSnapshot
import net.minecraft.block.Block
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos

object MinecraftBlockRestorer {
    data class RestoreResult(
        val success: Boolean,
        val reason: String? = null
    )

    fun restore(world: ServerWorld, position: BlockPosition, target: BlockStateSnapshot): RestoreResult {
        val pos = BlockPos(position.x, position.y, position.z)
        val state = runCatching { BlockStateSnapshotDecoder.decode(target) }
            .getOrElse { return RestoreResult(false, it.message ?: "Unable to decode recorded block state") }

        if (!world.setBlockState(pos, state, Block.NOTIFY_ALL or Block.FORCE_STATE)) {
            return RestoreResult(false, "Minecraft rejected the recorded block state")
        }

        val payload = target.blockEntityData
        if (payload != null) {
            val blockEntity = world.getBlockEntity(pos)
                ?: return RestoreResult(false, "Recorded block entity is missing after restoration")
            val nbt = runCatching { MinecraftNbtPayloadCodec.decode(payload) }
                .getOrElse { return RestoreResult(false, it.message ?: "Unable to decode recorded block-entity data") }
            runCatching {
                blockEntity.read(nbt, world.registryManager)
                blockEntity.markDirty()
                world.updateListeners(pos, state, state, Block.NOTIFY_LISTENERS)
            }.getOrElse {
                return RestoreResult(false, it.message ?: "Unable to restore recorded block-entity data")
            }
        }

        return RestoreResult(true)
    }
}
