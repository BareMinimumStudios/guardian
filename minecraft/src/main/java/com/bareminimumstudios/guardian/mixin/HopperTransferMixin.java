package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.HopperTransferCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(HopperBlockEntity.class)
public abstract class HopperTransferMixin {
    @WrapMethod(method = "ejectItems")
    private static boolean guardian$capturePush(Level level, BlockPos position, HopperBlockEntity hopper, Operation<Boolean> original) {
        try (var pending = HopperTransferCapture.beginPush(level, hopper)) {
            boolean result = original.call(level, position, hopper);
            HopperTransferCapture.finish(pending);
            return result;
        }
    }

    @WrapMethod(method = "suckInItems")
    private static boolean guardian$capturePull(Level level, Hopper hopper, Operation<Boolean> original) {
        try (var pending = HopperTransferCapture.beginPull(level, hopper)) {
            boolean result = original.call(level, hopper);
            HopperTransferCapture.finish(pending);
            return result;
        }
    }
}
