package com.bareminimumstudios.guardian.mixin;
import com.bareminimumstudios.guardian.platform.minecraft.EnvironmentHistoryCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.InteractionResult;
@Mixin(BoneMealItem.class)
public abstract class BoneMealGrowthHistoryMixin {
    @WrapMethod(method="useOn")
    private InteractionResult guardian$boneMeal(UseOnContext context, Operation<InteractionResult> original) {
        EnvironmentHistoryCapture.Scope scope=EnvironmentHistoryCapture.enterBoneMeal(context);
        EnvironmentHistoryCapture.ObservationBatch batch=EnvironmentHistoryCapture.beginObservation(context.getLevel());
        InteractionResult result=null;
        try { result=original.call(context);return result; }
        finally { try { EnvironmentHistoryCapture.finishObservation(batch,result!=null && result.consumesAction()); } finally { EnvironmentHistoryCapture.exit(scope); } }
    }
}
