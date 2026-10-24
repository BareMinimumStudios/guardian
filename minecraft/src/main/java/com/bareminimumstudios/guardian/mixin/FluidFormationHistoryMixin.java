package com.bareminimumstudios.guardian.mixin;
import com.bareminimumstudios.guardian.platform.minecraft.FluidFlowCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.world.level.block.LiquidBlock;
@Mixin(LiquidBlock.class)
public abstract class FluidFormationHistoryMixin {
    @WrapMethod(method="shouldSpreadLiquid")
    private boolean guardian$form(Level level, BlockPos pos, BlockState state, Operation<Boolean> original) {
        FluidFlowCapture.Scope scope=FluidFlowCapture.enter(level,pos,true);
        try { return original.call(level,pos,state); } finally { FluidFlowCapture.exit(scope); }
    }
}
