package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.PlayerBlockCapture;
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
public abstract class BlockInteractionMixin {
    @WrapMethod(method = "useItemOn")
    private InteractionResult guardian$captureInteraction(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
                                                          BlockHitResult hit, Operation<InteractionResult> original) {
        var customSignBefore = com.bareminimumstudios.guardian.platform.minecraft.SignHistoryCapture.beginCustom(player, hit.getBlockPos());
        var lecternBefore = com.bareminimumstudios.guardian.platform.minecraft.LecternHistoryCapture.before(player, hit.getBlockPos());
        var signBefore = com.bareminimumstudios.guardian.platform.minecraft.SignHistoryCapture.beginInteraction(level, player, hit.getBlockPos());
        var state = level.getBlockState(hit.getBlockPos());
        net.minecraft.core.BlockPos other = null;
        if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
            other = state.getValue(net.minecraft.world.level.block.DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
                    ? hit.getBlockPos().above() : hit.getBlockPos().below();
        }
        var otherBefore = other == null ? null : PlayerBlockCapture.beginInteraction(level, player, other);
        var before = PlayerBlockCapture.beginInteraction(level, player, hit.getBlockPos());
        var itemsBefore = com.bareminimumstudios.guardian.platform.minecraft.PlayerContainerCapture.beginBlockUse(level, player, hit.getBlockPos());
        InteractionResult result;
        try {
            result = original.call(player, level, stack, hand, hit);
        } catch (Throwable failure) {
            com.bareminimumstudios.guardian.platform.minecraft.PlayerContainerCapture.finishBlockUse(player, itemsBefore, false);
            throw failure;
        }
        com.bareminimumstudios.guardian.platform.minecraft.PlayerContainerCapture.finishBlockUse(player, itemsBefore, result.consumesAction());
        com.bareminimumstudios.guardian.platform.minecraft.SignHistoryCapture.finishCustom(player, customSignBefore, result.consumesAction());
        com.bareminimumstudios.guardian.platform.minecraft.SignHistoryCapture.finishInteraction(level, player, signBefore, result.consumesAction());
        com.bareminimumstudios.guardian.platform.minecraft.LecternHistoryCapture.after(player, lecternBefore, result.consumesAction(), true);
        PlayerBlockCapture.finishInteraction(level, player, hit.getBlockPos(), before, result);
        if (other != null) PlayerBlockCapture.finishInteraction(level, player, other, otherBefore, result);
        return result;
    }
}
