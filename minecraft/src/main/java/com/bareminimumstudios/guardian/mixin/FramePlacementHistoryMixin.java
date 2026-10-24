package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.ItemFrameHistoryCapture;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(HangingEntityItem.class)
public abstract class FramePlacementHistoryMixin {
    @WrapOperation(method = "useOn",at = @At(value = "INVOKE",target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"),require = 1)
    private boolean guardian$placeFrame(Level level,Entity entity,Operation<Boolean> original,UseOnContext context) {
        boolean result=original.call(level,entity);
        if(result && entity instanceof ItemFrame frame) ItemFrameHistoryCapture.placed(frame,context.getPlayer());
        return result;
    }
}
