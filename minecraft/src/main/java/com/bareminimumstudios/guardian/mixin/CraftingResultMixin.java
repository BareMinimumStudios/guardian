package com.bareminimumstudios.guardian.mixin;
import com.bareminimumstudios.guardian.platform.minecraft.PlayerContainerCapture;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** A completed take marks the outer click; merely displaying a result does not. */
@Mixin(ResultSlot.class)
public abstract class CraftingResultMixin {
    @Inject(method = "onTake", at = @At("TAIL"))
    private void guardian$taken(Player player, ItemStack stack, CallbackInfo ci) {
        PlayerContainerCapture.markCrafted(player);
    }
}
