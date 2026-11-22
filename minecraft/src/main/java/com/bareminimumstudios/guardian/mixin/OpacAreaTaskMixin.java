package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.OpacTaskHooks;
import com.bareminimumstudios.guardian.platform.minecraft.OpacRequesterCapture;
import com.bareminimumstudios.guardian.platform.minecraft.OpacStructureProtection;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

@Pseudo
@Mixin(targets="xaero.pac.common.server.claims.player.task.PlayerAreaClaimActionSpreadoutTask",remap=false)
public abstract class OpacAreaTaskMixin implements OpacTaskHooks {
    @Unique private OpacRequesterCapture guardian$requester;
    @Inject(method="<init>",at=@At("RETURN"))
    private void guardian$capture(CallbackInfo result) { guardian$requester=OpacStructureProtection.captureRequester(); }
    @Override public OpacRequesterCapture guardian$requester() { return guardian$requester; }
    @WrapMethod(method="onQueued")
    private void guardian$queued(@Coerce Object data,Operation<Void> original) {
        OpacStructureProtection.withTask(guardian$requester,()->original.call(data));
    }
    @WrapMethod(method="onTick(Lxaero/pac/common/server/IServerData;Ljava/lang/Object;ILjava/util/List;)V")
    private void guardian$tick(@Coerce Object data,Object task,int budget,List<?> finished,Operation<Void> original) {
        OpacStructureProtection.withTask(guardian$requester,()->original.call(data,task,budget,finished));
    }
}
