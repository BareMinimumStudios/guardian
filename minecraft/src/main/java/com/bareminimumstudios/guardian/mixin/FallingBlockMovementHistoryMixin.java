package com.bareminimumstudios.guardian.mixin;
import com.bareminimumstudios.guardian.platform.minecraft.BlockMovementCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import net.minecraft.world.entity.item.FallingBlockEntity;
import com.bareminimumstudios.guardian.domain.BlockStateSnapshot;
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockMovementHistoryMixin {
    @WrapMethod(method="fall")
    private static FallingBlockEntity guardian$source(Level level, BlockPos pos, BlockState state, Operation<FallingBlockEntity> original) {
        BlockMovementCapture.FallStart start=BlockMovementCapture.beginFall(level,pos);
        FallingBlockEntity entity=original.call(level,pos,state);BlockMovementCapture.endFall(start,entity);return entity;
    }
    @WrapMethod(method="tick")
    private void guardian$fallingTick(Operation<Void> original) {
        BlockMovementCapture.FallTick frame=BlockMovementCapture.beginFallTick((FallingBlockEntity)(Object)this);
        try { original.call(); } finally { BlockMovementCapture.endFallTick(frame); }
    }
    @WrapOperation(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean guardian$landing(Level level, BlockPos pos, BlockState state, int flags, Operation<Boolean> original) {
        BlockStateSnapshot before=BlockMovementCapture.beforeLanding(level,pos);
        boolean result=original.call(level,pos,state,flags);BlockMovementCapture.landed(pos,before,result);return result;
    }
}
