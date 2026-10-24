package com.bareminimumstudios.guardian.mixin;
import com.bareminimumstudios.guardian.platform.minecraft.EnvironmentHistoryCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
@Mixin(FireBlock.class)
public abstract class FireTickHistoryMixin {
    @WrapMethod(method="tick")
    private void guardian$fireTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, Operation<Void> original) {
        EnvironmentHistoryCapture.Scope scope=EnvironmentHistoryCapture.enterFire(level,null);
        try { original.call(state,level,pos,random); } finally { EnvironmentHistoryCapture.exit(scope); }
    }
}
