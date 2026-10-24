package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.EntityHistoryCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(TamableAnimal.class)
public abstract class TameEntityHistoryMixin {
    @WrapMethod(method = "tame")
    private void guardian$tame(Player player, Operation<Void> original) {
        TamableAnimal entity=(TamableAnimal)(Object)this;
        boolean before=entity.isTame();
        original.call(player);
        if (!before && entity.isTame() && player.getUUID().equals(entity.getOwnerUUID())) EntityHistoryCapture.tamed(entity,player);
    }
}
