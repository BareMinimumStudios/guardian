package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.EntityHistoryCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(AbstractHorse.class)
public abstract class HorseTameHistoryMixin {
    @WrapMethod(method = "tameWithName")
    private boolean guardian$tameHorse(Player player, Operation<Boolean> original) {
        AbstractHorse entity=(AbstractHorse)(Object)this;
        boolean before=entity.isTamed();
        boolean accepted=original.call(player);
        if (accepted && !before && entity.isTamed() && player.getUUID().equals(entity.getOwnerUUID())) EntityHistoryCapture.tamed(entity,player);
        return accepted;
    }
}
