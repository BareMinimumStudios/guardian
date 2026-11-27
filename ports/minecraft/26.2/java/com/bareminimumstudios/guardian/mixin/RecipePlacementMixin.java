package com.bareminimumstudios.guardian.mixin;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
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
@Mixin({AbstractCraftingMenu.class, AbstractFurnaceMenu.class})
public abstract class RecipePlacementMixin {
    @WrapMethod(method = "handlePlacement")
    private RecipeBookMenu.PostPlaceAction guardian$recipe(boolean all, boolean allowDropping, RecipeHolder<?> recipe, ServerLevel level, Inventory inventory, Operation<RecipeBookMenu.PostPlaceAction> original) {
        if (!(inventory.player instanceof ServerPlayer player)) return original.call(all,allowDropping,recipe,level,inventory);
        if (!MinecraftInventoryCoordination.allowsMenuMutation(player, (AbstractContainerMenu)(Object)this)) {
            MinecraftInventoryCoordination.resynchronize(player);
            return RecipeBookMenu.PostPlaceAction.NOTHING;
        }
        try (var pending = PlayerContainerCapture.beginRecipe((AbstractContainerMenu)(Object)this, player)) {
            var result = original.call(all, allowDropping, recipe, level, inventory);
            PlayerContainerCapture.finish(pending, player);
            return result;
        }
    }
}
