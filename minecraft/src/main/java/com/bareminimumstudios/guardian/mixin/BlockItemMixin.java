package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.PlayerBlockCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Resolve the actual position, then commit accepted placements with exception-safe frame cleanup. */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @WrapMethod(method = "place")
    private InteractionResult guardian$placementFrame(BlockPlaceContext context, Operation<InteractionResult> original) {
        PlayerBlockCapture.enterPlacement();
        boolean returned = false;
        try {
            InteractionResult result = original.call(context);
            returned = true;
            PlayerBlockCapture.finishPlacement(result);
            return result;
        } finally {
            if (!returned) PlayerBlockCapture.abortPlacement();
        }
    }

    @Redirect(method = "place", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/BlockItem;updatePlacementContext(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/item/context/BlockPlaceContext;"))
    private BlockPlaceContext guardian$captureResolvedPlacementContext(BlockItem instance, BlockPlaceContext originalContext) {
        BlockPlaceContext resolved = instance.updatePlacementContext(originalContext);
        PlayerBlockCapture.beginPlacement(resolved);
        return resolved;
    }
}
