package com.bareminimumstudios.guardian.mixin;
import com.bareminimumstudios.guardian.platform.minecraft.FluidFlowCapture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ServerLevel.class)
public abstract class FluidTraceUnloadMixin {
    @Inject(method="unload",at=@At("HEAD"))
    private void guardian$forgetFluidSources(LevelChunk chunk, CallbackInfo ci) {
        FluidFlowCapture.unload((ServerLevel)(Object)this,chunk.getPos().x,chunk.getPos().z);
    }
}
