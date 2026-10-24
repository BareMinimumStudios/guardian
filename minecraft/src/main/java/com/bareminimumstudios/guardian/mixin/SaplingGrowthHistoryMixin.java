package com.bareminimumstudios.guardian.mixin;
import com.bareminimumstudios.guardian.platform.minecraft.EnvironmentHistoryCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.world.level.block.SaplingBlock;
@Mixin(SaplingBlock.class)
public abstract class SaplingGrowthHistoryMixin {
    @WrapMethod(method="advanceTree")
    private void guardian$tree(ServerLevel level, BlockPos pos, BlockState state, RandomSource random, Operation<Void> original) {
        EnvironmentHistoryCapture.Scope scope=EnvironmentHistoryCapture.enterGrowth(level);
        EnvironmentHistoryCapture.ObservationBatch batch=EnvironmentHistoryCapture.beginObservation(level);
        boolean completed=false;
        try { original.call(level,pos,state,random);completed=true; }
        finally { try { EnvironmentHistoryCapture.finishObservation(batch,completed); } finally { EnvironmentHistoryCapture.exit(scope); } }
    }
}
