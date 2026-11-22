package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.OpacRequestHooks;
import com.bareminimumstudios.guardian.platform.minecraft.OpacStructureProtection;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets="xaero.pac.common.server.claims.player.request.PlayerClaimActionRequestHandler",remap=false)
public abstract class OpacRequestContextMixin implements OpacRequestHooks {
    @WrapMethod(method="onReceive")
    private void guardian$request(ServerPlayer player,@Coerce Object request,Operation<Void> original) {
        OpacStructureProtection.withRequester(player,()->original.call(player,request));
    }
}
