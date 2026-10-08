package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerPlayerGameMode.class)
public abstract class PlayerUseMixin {
    @WrapMethod(method = "useItem")
    private InteractionResult guardian$guardUse(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, Operation<InteractionResult> original) {
        if (!MinecraftInventoryCoordination.allowsAutomation(level)) {
            if (player.server.isSameThread()) MinecraftInventoryCoordination.resynchronize(player);
            return InteractionResult.FAIL;
        }
        return original.call(player, level, stack, hand);
    }

    @WrapMethod(method = "useItemOn")
    private InteractionResult guardian$guardUseOn(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit, Operation<InteractionResult> original) {
        if (!MinecraftInventoryCoordination.allowsAutomation(level)) {
            if (player.server.isSameThread()) MinecraftInventoryCoordination.resynchronize(player);
            return InteractionResult.FAIL;
        }
        return original.call(player, level, stack, hand, hit);
    }
}
