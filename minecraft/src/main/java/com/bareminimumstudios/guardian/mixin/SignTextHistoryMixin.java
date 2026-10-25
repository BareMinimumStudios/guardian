package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.SignHistoryCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import java.util.List;

@Mixin(SignBlockEntity.class)
public abstract class SignTextHistoryMixin {
    @WrapMethod(method="updateSignText")
    private void guardian$signText(Player player, boolean front, List<FilteredText> lines, Operation<Void> original) {
        SignBlockEntity sign=(SignBlockEntity)(Object)this;
        SignHistoryCapture.Pending pending=SignHistoryCapture.before(sign,player,front);
        original.call(player,front,lines);
        SignHistoryCapture.after(sign,player,pending);
    }
}
