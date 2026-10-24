package com.bareminimumstudios.guardian.mixin;
import com.bareminimumstudios.guardian.platform.minecraft.EnvironmentHistoryCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(Level.class)
public abstract class EnvironmentMutationHistoryMixin {
    @WrapMethod(method="setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")
    private boolean guardian$environmentMutation(BlockPos pos, BlockState state, int flags, int recursion, Operation<Boolean> original) {
        EnvironmentHistoryCapture.Pending pending=EnvironmentHistoryCapture.before((Level)(Object)this,pos,state);
        boolean result=false;
        try { result=original.call(pos,state,flags,recursion);return result; }
        finally { EnvironmentHistoryCapture.after(pending,result); }
    }
}
