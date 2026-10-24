package com.bareminimumstudios.guardian.mixin;
import com.bareminimumstudios.guardian.platform.minecraft.EnvironmentHistoryCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.world.level.block.CropBlock;
@Mixin(CropBlock.class)
public abstract class CropGrowthHistoryMixin {
    @WrapMethod(method="randomTick")
    private void guardian$naturalTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, Operation<Void> original) {
        EnvironmentHistoryCapture.Scope scope=EnvironmentHistoryCapture.enterGrowth(level);
        EnvironmentHistoryCapture.ObservationBatch batch=EnvironmentHistoryCapture.beginObservation(level);
        boolean completed=false;
        try { original.call(state,level,pos,random);completed=true; }
        finally { try { EnvironmentHistoryCapture.finishObservation(batch,completed); } finally { EnvironmentHistoryCapture.exit(scope); } }
    }
}
