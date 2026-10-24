package com.bareminimumstudios.guardian.mixin;
import com.bareminimumstudios.guardian.platform.minecraft.EnvironmentHistoryCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.world.level.Explosion;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
@Mixin(Explosion.class)
public abstract class ExplosionHistoryMixin {
    @Shadow @Final private Level level;
    @WrapMethod(method="finalizeExplosion")
    private void guardian$explosion(boolean particles, Operation<Void> original) {
        EnvironmentHistoryCapture.Scope scope=EnvironmentHistoryCapture.enterExplosion(level,(Explosion)(Object)this);
        try { original.call(particles); } finally { EnvironmentHistoryCapture.exit(scope); }
    }
}
