package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.OpacReplacementHooks;
import com.bareminimumstudios.guardian.platform.minecraft.OpacRequesterCapture;
import com.bareminimumstudios.guardian.platform.minecraft.OpacStructureProtection;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

@Pseudo
@Mixin(targets="xaero.pac.common.server.claims.player.task.PlayerClaimReplaceSpreadoutTask",remap=false)
public abstract class OpacReplacementTaskMixin implements OpacReplacementHooks {
    @Shadow @Final @Mutable private BiPredicate<Object,ChunkPos> posFilter;
    @Unique private OpacRequesterCapture guardian$requester;
    @Unique private boolean guardian$filtered;
    @Unique private Object guardian$dimension;
    @Inject(method="<init>",at=@At("RETURN"))
    private void guardian$capture(CallbackInfo result) {
        guardian$requester=OpacStructureProtection.captureRequester();
        if(!guardian$filtered) {
            BiPredicate<Object,ChunkPos> previous=posFilter;
            posFilter=(dimensionClaims,pos)->(previous==null || previous.test(dimensionClaims,pos)) &&
                    OpacStructureProtection.allowReplacement(this,guardian$dimension,pos);
            guardian$filtered=true;
        }
    }
    @Override public OpacRequesterCapture guardian$requester() { return guardian$requester; }
    // OPAC passes IPlayerDimensionClaims to its predicate. Its map key is the actual
    // dimension ID and is captured before the predicate and any unclaim operation.
    @WrapOperation(method={"onTick(Lxaero/pac/common/server/IServerData;Lxaero/pac/common/server/claims/player/task/PlayerClaimReplaceSpreadoutTask;ILjava/util/List;)V","onQueued"},
        at=@At(value="INVOKE",target="Ljava/util/Map$Entry;getKey()Ljava/lang/Object;",remap=false))
    private Object guardian$dimension(Map.Entry<?,?> entry,Operation<Object> original) {
        Object dimension=original.call(entry);guardian$dimension=dimension;return dimension;
    }
    @WrapMethod(method="onQueued")
    private void guardian$queued(@Coerce Object data,Operation<Void> original) {
        Object previous=guardian$dimension;guardian$dimension=null;
        try { OpacStructureProtection.withTask(guardian$requester,()->original.call(data)); }
        finally { guardian$dimension=previous; }
    }
    @WrapMethod(method="onTick(Lxaero/pac/common/server/IServerData;Ljava/lang/Object;ILjava/util/List;)V")
    private void guardian$tick(@Coerce Object data,Object task,int budget,List<?> finished,Operation<Void> original) {
        Object previous=guardian$dimension;guardian$dimension=null;
        try { OpacStructureProtection.withTask(guardian$requester,()->original.call(data,task,budget,finished)); }
        finally { guardian$dimension=previous; }
    }
}
