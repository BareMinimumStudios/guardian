package com.bareminimumstudios.guardian.mixin;
import com.bareminimumstudios.guardian.platform.minecraft.FluidFlowCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(FluidInteractionRegistry.class)
public abstract class NeoForgeFluidFormationHistoryMixin {
    @WrapMethod(method="canInteract",remap=false)
    private static boolean guardian$form(Level level, BlockPos pos, Operation<Boolean> original) {
        FluidFlowCapture.Scope scope=FluidFlowCapture.enter(level,pos,true);
        try { return original.call(level,pos); } finally { FluidFlowCapture.exit(scope); }
    }
}
