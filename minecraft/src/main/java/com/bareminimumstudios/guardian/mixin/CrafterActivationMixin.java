package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(CrafterBlock.class)
public abstract class CrafterActivationMixin {
    @WrapMethod(method = "dispenseFrom")
    private void guardian$guardActivation(BlockState state, ServerLevel level, BlockPos position, Operation<Void> original) {
        if (MinecraftInventoryCoordination.allowsAutomation(level)) original.call(state, level, position);
    }
}
