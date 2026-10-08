package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(DropperBlock.class)
public abstract class DropperCoordinationMixin {
    @WrapMethod(method = "dispenseFrom")
    private void guardian$guardDispense(ServerLevel level, BlockState state, BlockPos position, Operation<Void> original) {
        if (MinecraftInventoryCoordination.allowsDispense(level)) original.call(level, state, position);
    }
}
