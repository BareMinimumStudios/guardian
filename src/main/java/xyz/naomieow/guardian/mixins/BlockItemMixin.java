package xyz.naomieow.guardian.mixins;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.naomieow.guardian.event.PlayerBlockEvent;

@Mixin(BlockItem.class)
abstract class BlockItemMixin {
    @Inject(
            at = @At(value = "HEAD"),
            method = "placeBlock(Lnet/minecraft/world/item/context/BlockPlaceContext;Lnet/minecraft/world/level/block/state/BlockState;)Z",
            cancellable = true
    )
    private void placeBlock(BlockPlaceContext ctx, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (ctx.getPlayer() != null) {
            var result = PlayerBlockEvent.Place.INSTANCE
                    .getBEFORE()
                    .getDispatcher()
                    .invoke(
                            ctx.getLevel(),
                            ctx.getPlayer(),
                            ctx.getClickedPos(),
                            state
                    );

            if (result) {
                PlayerBlockEvent.Place.INSTANCE
                    .getCANCELLED()
                    .getDispatcher()
                    .invoke(
                            ctx.getLevel(),
                            ctx.getPlayer(),
                            ctx.getClickedPos(),
                            state
                    );
                cir.setReturnValue(false);
            }
        }
    }

    @Inject(
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/Block;setPlacedBy(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)V"
            ),
            method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;"
    )
    private void place(
            BlockPlaceContext ctx,
            CallbackInfoReturnable<InteractionResult> cir,
            @Local(name = "$$7") BlockState state
    ) {
        var player = ctx.getPlayer();
        if (player != null) {
            PlayerBlockEvent.Place.INSTANCE
                    .getAFTER()
                    .getDispatcher()
                    .invoke(
                            ctx.getLevel(),
                            player,
                            ctx.getClickedPos(),
                            state
                    );
        }
    }
}
