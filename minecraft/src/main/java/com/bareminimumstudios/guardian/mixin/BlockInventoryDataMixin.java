package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class BlockInventoryDataMixin {
    @Inject(method = {"loadWithComponents", "loadCustomOnly"}, at = @At("HEAD"), cancellable = true)
    private void guardian$beforeDataLoad(CallbackInfo callback) {
        BlockEntity block = (BlockEntity) (Object) this;
        if (!MinecraftInventoryCoordination.allowsLiveBlockDataLoad(block)) {
            callback.cancel();
            return;
        }
        MinecraftInventoryCoordination.beforeBlockInventoryMutation(block);
    }

    @Inject(method = {"applyComponents", "applyComponentsFromItemStack"}, at = @At("HEAD"), cancellable = true)
    private void guardian$beforeDataWrite(CallbackInfo callback) {
        BlockEntity block = (BlockEntity) (Object) this;
        if (!MinecraftInventoryCoordination.allowsLiveBlockDataLoad(block)) {
            callback.cancel();
            return;
        }
        MinecraftInventoryCoordination.beforeBlockInventoryMutation(block);
    }
}
