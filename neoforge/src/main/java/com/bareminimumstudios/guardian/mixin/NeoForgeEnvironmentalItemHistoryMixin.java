package com.bareminimumstudios.guardian.mixin;
import com.bareminimumstudios.guardian.platform.minecraft.EnvironmentHistoryCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.InteractionResult;
import net.neoforged.neoforge.common.CommonHooks;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(CommonHooks.class)
public abstract class NeoForgeEnvironmentalItemHistoryMixin {
    @WrapMethod(method="onPlaceItemIntoWorld",remap=false)
    private static InteractionResult guardian$acceptedItemUse(UseOnContext context, Operation<InteractionResult> original) {
        EnvironmentHistoryCapture.ObservationBatch batch=EnvironmentHistoryCapture.beginObservation(context.getLevel());
        InteractionResult result=null;
        try { result=original.call(context);return result; }
        finally { EnvironmentHistoryCapture.finishObservation(batch,result!=null && result.consumesAction()); }
    }
}
