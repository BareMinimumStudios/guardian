package com.bareminimumstudios.guardian.mixin;
import com.bareminimumstudios.guardian.platform.minecraft.FluidFlowCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidHistoryMixin {
    @WrapMethod(method="tick")
    private void guardian$flow(Level level, BlockPos pos, FluidState state, Operation<Void> original) {
        FluidFlowCapture.Scope scope=FluidFlowCapture.enter(level,pos,false);
        try { original.call(level,pos,state); } finally { FluidFlowCapture.exit(scope); }
    }
}
