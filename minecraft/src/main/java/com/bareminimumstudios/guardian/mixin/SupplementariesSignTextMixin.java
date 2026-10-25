package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.SignHistoryCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import java.util.List;

/** Optional packet path after Supplementaries has filtered the text onto the server thread. */
@Pseudo
@Mixin(targets="net.mehvahdjukaar.supplementaries.common.network.ServerBoundSetTextHolderPacket", remap=false)
public abstract class SupplementariesSignTextMixin {
    @Shadow(remap=false) @Final private BlockPos pos;
    @WrapMethod(method="updateSignText", remap=false)
    private void guardian$captureAcceptedText(ServerPlayer player, List<?> lines, Operation<Void> original) {
        var before=SignHistoryCapture.beginCustom(player,pos);
        original.call(player,lines);
        SignHistoryCapture.finishCustom(player,before,true);
    }
}
