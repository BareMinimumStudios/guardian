package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LevelChunk.class)
public abstract class ContainerChunkLifecycleMixin {
    @WrapMethod(method = "setBlockState")
    private BlockState guardian$invalidateChange(BlockPos position, BlockState next, boolean moved, Operation<BlockState> original) {
        LevelChunk chunk = (LevelChunk)(Object)this;
        if (MinecraftInventoryCoordination.needsStructuralCheck(chunk.getLevel())) {
            BlockState previous = chunk.getBlockState(position);
            if (previous != next) MinecraftInventoryCoordination.beforeBlockChange(chunk.getLevel(), position, previous);
        }
        return original.call(position, next, moved);
    }

    @WrapMethod(method = "setBlockEntity")
    private void guardian$invalidateReplacement(BlockEntity block, Operation<Void> original) {
        LevelChunk chunk = (LevelChunk)(Object)this;
        if (MinecraftInventoryCoordination.needsStructuralCheck(chunk.getLevel())) {
            MinecraftInventoryCoordination.beforeBlockChange(chunk.getLevel(), block.getBlockPos(), chunk.getBlockState(block.getBlockPos()));
        }
        original.call(block);
    }

    @WrapMethod(method = "clearAllBlockEntities")
    private void guardian$invalidateUnload(Operation<Void> original) {
        MinecraftInventoryCoordination.beforeChunkUnload((LevelChunk)(Object)this);
        original.call();
    }
}
