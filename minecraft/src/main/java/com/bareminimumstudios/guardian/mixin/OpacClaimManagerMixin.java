package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.OpacClaimHooks;
import com.bareminimumstudios.guardian.platform.minecraft.OpacStructureProtection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.UUID;
import java.util.function.Consumer;

@Pseudo
@Mixin(targets="xaero.pac.common.server.claims.ServerClaimsManager",remap=false)
public abstract class OpacClaimManagerMixin implements OpacClaimHooks {
    @Inject(method="tryToClaimHelper",at=@At("HEAD"),cancellable=true)
    private void guardian$claim(@Coerce Object dimension,UUID owner,int subIndex,int fromX,int fromZ,int x,int z,
            boolean forceLoaded,boolean force,boolean server,int limit,@Coerce Object permission,@Coerce Object action,
            CallbackInfoReturnable<Object> result) {
        Object refusal=OpacStructureProtection.beforeClaim(this,dimension,owner,x,z);
        if(refusal!=null)result.setReturnValue(refusal);
    }
    @Inject(method="tryAnchor",at=@At("HEAD"),cancellable=true)
    private void guardian$anchor(@Coerce Object dimension,UUID owner,@Coerce Object fromDimension,int fromX,int fromZ,int x,int z,
            boolean add,boolean force,Consumer<?> listener,CallbackInfoReturnable<Object> result) {
        if(!add)return;
        Object refusal=OpacStructureProtection.beforeClaim(this,dimension,owner,x,z);
        if(refusal!=null)result.setReturnValue(refusal);
    }
    @Inject(method="claim",at=@At("HEAD"))
    private void guardian$direct(@Coerce Object dimension,UUID owner,int subIndex,int x,int z,boolean forceLoaded,
            CallbackInfoReturnable<Object> result) {
        OpacStructureProtection.directClaim(this,dimension,owner,x,z);
    }
}
