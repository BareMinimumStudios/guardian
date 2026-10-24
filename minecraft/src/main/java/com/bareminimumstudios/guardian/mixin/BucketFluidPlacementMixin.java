package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.FluidHistoryCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BucketItem.class)
public abstract class BucketFluidPlacementMixin {
    @WrapMethod(method = "emptyContents(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;)Z")
    private boolean guardian$placeFluid(Player player, Level level, BlockPos pos, BlockHitResult hit, Operation<Boolean> original) {
        FluidHistoryCapture.Pending pending=FluidHistoryCapture.begin(player,level,pos);
        boolean result=original.call(player,level,pos,hit);
        FluidHistoryCapture.finish(pending,result,false);
        return result;
    }
}
