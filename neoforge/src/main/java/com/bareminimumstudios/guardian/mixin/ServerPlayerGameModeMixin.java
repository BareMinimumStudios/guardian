package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.domain.BlockStateSnapshot;
import com.bareminimumstudios.guardian.platform.minecraft.PlayerBlockCapture;
import com.bareminimumstudios.guardian.platform.minecraft.MinecraftBlockSnapshotter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.ArrayDeque;

/** Brackets the accepted destruction result, after NeoForge protection hooks have had their say. */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
    @Shadow protected ServerLevel level;
    @Shadow protected ServerPlayer player;
    @Unique private final ArrayDeque<GuardianBreakFrame> guardian$breaks = new ArrayDeque<>();
    @Unique private record GuardianBreakFrame(BlockPos pos, BlockStateSnapshot before) {}

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void guardian$beforeBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockStateSnapshot before = PlayerBlockCapture.beginBreak(level, player, pos);
        guardian$breaks.addLast(new GuardianBreakFrame(pos.immutable(), before));
    }
    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void guardian$afterBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        GuardianBreakFrame frame = guardian$breaks.pollLast();
        if (frame != null && Boolean.TRUE.equals(cir.getReturnValue())) {
            PlayerBlockCapture.finishBreak(level, player, frame.pos(), frame.before());
        }
    }
}
