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
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
@Mixin(PistonBaseBlock.class)
public abstract class PistonMovementHistoryMixin {
    @Shadow @Final private boolean isSticky;
    @WrapMethod(method="moveBlocks")
    private boolean guardian$movement(Level level, BlockPos pos, Direction direction, boolean extending, Operation<Boolean> original) {
        BlockMovementCapture.PistonFrame frame=BlockMovementCapture.beginPiston(level,pos,direction,extending,isSticky);
        boolean result=false;
        try { result=original.call(level,pos,direction,extending);return result; }
        finally { BlockMovementCapture.endPiston(frame,result); }
    }
    @WrapOperation(method="moveBlocks",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/piston/PistonStructureResolver;resolve()Z"))
    private boolean guardian$resolvedPlan(PistonStructureResolver resolver, Operation<Boolean> original) {
        boolean result=original.call(resolver);BlockMovementCapture.resolved(resolver,result);return result;
    }
}
