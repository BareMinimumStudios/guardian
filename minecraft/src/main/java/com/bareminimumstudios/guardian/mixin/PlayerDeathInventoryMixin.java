package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
public abstract class PlayerDeathInventoryMixin {
    @WrapOperation(method = "destroyVanishingCursedItems", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;removeItemNoUpdate(I)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack guardian$removeVanishingItem(Inventory inventory, int slot, Operation<ItemStack> original) {
        return MinecraftInventoryCoordination.removeLifecycleVanishingItem(inventory, slot, () -> original.call(inventory, slot));
    }
}
