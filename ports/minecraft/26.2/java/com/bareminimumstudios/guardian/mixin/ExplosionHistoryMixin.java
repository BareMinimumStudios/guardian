package com.bareminimumstudios.guardian.mixin;
import com.bareminimumstudios.guardian.platform.minecraft.EnvironmentHistoryCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
@Mixin(ServerExplosion.class)
public abstract class ExplosionHistoryMixin {
    @Shadow @Final private ServerLevel level;
    @WrapMethod(method="explode")
    private int guardian$explosion(Operation<Integer> original) {
        EnvironmentHistoryCapture.Scope scope=EnvironmentHistoryCapture.enterExplosion(level,(ServerExplosion)(Object)this);
        try { return original.call(); } finally { EnvironmentHistoryCapture.exit(scope); }
    }
}
