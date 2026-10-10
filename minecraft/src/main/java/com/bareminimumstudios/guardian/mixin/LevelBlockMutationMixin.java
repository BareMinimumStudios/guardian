package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Refuse complete world mutation callers before drops, snapshots or removal callbacks. */
@Mixin(Level.class)
public abstract class LevelBlockMutationMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeReplacement(BlockPos position, BlockState state, int flags, int recursion,
                                            CallbackInfoReturnable<Boolean> callback) {
        if (!MinecraftInventoryCoordination.allowsWorldBlockMutation((Level)(Object)this)) callback.setReturnValue(false);
    }

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeDestruction(BlockPos position, boolean drops, Entity entity, int recursion,
                                            CallbackInfoReturnable<Boolean> callback) {
        if (!MinecraftInventoryCoordination.allowsWorldBlockMutation((Level)(Object)this)) callback.setReturnValue(false);
    }

    @Inject(method = "removeBlock", at = @At("HEAD"), cancellable = true)
    private void guardian$beforeRemoval(BlockPos position, boolean moved, CallbackInfoReturnable<Boolean> callback) {
        if (!MinecraftInventoryCoordination.allowsWorldBlockMutation((Level)(Object)this)) callback.setReturnValue(false);
    }
}
