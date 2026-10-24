package com.bareminimumstudios.guardian.mixin;
import com.bareminimumstudios.guardian.platform.minecraft.EnvironmentHistoryCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.InteractionResult;
@Mixin({FlintAndSteelItem.class,FireChargeItem.class})
public abstract class IgnitionHistoryMixin {
    @WrapMethod(method="useOn")
    private InteractionResult guardian$ignite(UseOnContext context, Operation<InteractionResult> original) {
        EnvironmentHistoryCapture.Scope scope=EnvironmentHistoryCapture.enterIgnition(context);
        try { return original.call(context); } finally { EnvironmentHistoryCapture.exit(scope); }
    }
}
