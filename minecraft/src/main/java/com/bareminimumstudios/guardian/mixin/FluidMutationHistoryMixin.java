package com.bareminimumstudios.guardian.mixin;
import com.bareminimumstudios.guardian.platform.minecraft.FluidFlowCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(Level.class)
public abstract class FluidMutationHistoryMixin {
    @WrapMethod(method="setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")
    private boolean guardian$fluidMutation(BlockPos pos, BlockState state, int flags, int recursion, Operation<Boolean> original) {
        Level level=(Level)(Object)this;
        FluidFlowCapture.Mutation pending=FluidFlowCapture.before(level,pos);
        boolean result=original.call(pos,state,flags,recursion);
        FluidFlowCapture.after(pending,result);
        return result;
    }
}
