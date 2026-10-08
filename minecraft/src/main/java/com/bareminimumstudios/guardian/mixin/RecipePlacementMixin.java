package com.bareminimumstudios.guardian.mixin;
import com.bareminimumstudios.guardian.platform.minecraft.PlayerContainerCapture;
import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
/** Bracket authoritative recipe-book grid placement, separately from taking its result. */
@Mixin(RecipeBookMenu.class)
public abstract class RecipePlacementMixin {
    @WrapMethod(method = "handlePlacement")
    private void guardian$recipe(boolean all, RecipeHolder<?> recipe, ServerPlayer player, Operation<Void> original) {
        if (!MinecraftInventoryCoordination.allowsMenuMutation(player, (AbstractContainerMenu)(Object)this)) {
            MinecraftInventoryCoordination.resynchronize(player);
            return;
        }
        try (var pending = PlayerContainerCapture.beginRecipe((AbstractContainerMenu)(Object)this, player)) {
            original.call(all, recipe, player);
            PlayerContainerCapture.finish(pending, player);
        }
    }
}
