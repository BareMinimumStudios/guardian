package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.PlayerBlockCapture;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.ActionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Placement-only hook for Minecraft 1.21.1.
 *
 * We redirect BlockItem's own getPlacementContext call so specialized items such as scaffolding
 * can resolve/offset their real placement position before Guardian takes the BEFORE snapshot.
 * The RETURN hook only commits a capture when vanilla reports an accepted placement result.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

    @Redirect(
        method = "place",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/item/BlockItem;getPlacementContext(Lnet/minecraft/item/ItemPlacementContext;)Lnet/minecraft/item/ItemPlacementContext;"
        )
    )
    private ItemPlacementContext guardian$captureResolvedPlacementContext(
        BlockItem instance,
        ItemPlacementContext originalContext
    ) {
        ItemPlacementContext resolved = instance.getPlacementContext(originalContext);
        PlayerBlockCapture.beginPlacement(resolved);
        return resolved;
    }

    @Inject(method = "place", at = @At("RETURN"))
    private void guardian$finishPlacement(
        ItemPlacementContext context,
        CallbackInfoReturnable<ActionResult> cir
    ) {
        PlayerBlockCapture.finishPlacement(cir.getReturnValue());
    }
}
