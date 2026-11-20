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

        val decodedPayload = runCatching { target.blockEntityData?.let(MinecraftNbtPayloadCodec::decode) }
            .getOrElse { return RestoreResult(false, it.message ?: "Recorded block-entity data is incompatible") }

        // An unchanged state makes vanilla setBlock return false, but its block-entity data
        // may still need restoration. Avoid replacing the existing entity in that case.
        if (world.getBlockState(pos) != state && !world.setBlock(pos, state, Block.UPDATE_ALL or Block.UPDATE_KNOWN_SHAPE)) {
            return RestoreResult(false, "Minecraft rejected the recorded block state")
        }

        if (world.getBlockState(pos) != state) return RestoreResult(false, "Recorded block state was changed during restoration")

        val payload = target.blockEntityData
        if (payload != null) {
            val blockEntity = world.getBlockEntity(pos)
                ?: return RestoreResult(false, "Recorded block entity is missing after restoration")
            val nbt = checkNotNull(decodedPayload)
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
