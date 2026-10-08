package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(CrafterBlockEntity.class)
public abstract class CrafterTickMixin {
    @WrapMethod(method = "serverTick")
    private static void guardian$guardTick(Level level, BlockPos position, BlockState state, CrafterBlockEntity block, Operation<Void> original) {
        if (MinecraftInventoryCoordination.allowsAutomation(level)) original.call(level, position, state, block);
    }
}
