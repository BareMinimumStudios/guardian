package xyz.naomieow.guardian.mixins;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.naomieow.guardian.event.BlockEvent;

@Mixin(ServerPlayerGameMode.class)
abstract class ServerPlayerGameModeMixin {

    @Shadow
    protected ServerLevel level;

    @Shadow
    @Final
    protected ServerPlayer player;

    @Inject(
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/Block;playerWillDestroy(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/level/block/state/BlockState;"
            ),
            method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z",
            cancellable = true
    )
    private void breakBlock(
            BlockPos pos,
            CallbackInfoReturnable<Boolean> cir,
            @Local(name = "$$2") BlockEntity blockEntity,
            @Local(name = "$$1") BlockState state
    ) {
        var result = BlockEvent.Break.INSTANCE
                .getBEFORE()
                .getDispatcher()
                .invoke(
                        this.level,
                        this.player,
                        pos,
                        state,
                        blockEntity
                );

        if (result) {
            BlockEvent.Break.INSTANCE
                    .getCANCELLED()
                    .getDispatcher()
                    .invoke(
                            this.level,
                            this.player,
                            pos,
                            state,
                            blockEntity
                    );
            cir.setReturnValue(false);
        }
    }

    @Inject(
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/Block;destroy(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"
            ),
            method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z"
    )
    private void onBlockBroken(
            BlockPos pos,
            CallbackInfoReturnable<Boolean> cir,
            @Local(ordinal = 0) BlockState state,
            @Local(name = "$$2") BlockEntity blockEntity,
            @Local(name = "$$3") Block block
    ) {
        BlockEvent.Break.INSTANCE
                .getAFTER()
                .getDispatcher()
                .invoke(
                        this.level,
                        this.player,
                        pos,
                        state,
                        blockEntity
                );
    }
}








