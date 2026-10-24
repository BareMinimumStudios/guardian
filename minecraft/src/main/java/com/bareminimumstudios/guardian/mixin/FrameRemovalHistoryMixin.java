package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.ItemFrameHistoryCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Entity.class)
public abstract class FrameRemovalHistoryMixin {
    @WrapMethod(method = "remove")
    private void guardian$removeFrame(Entity.RemovalReason reason,Operation<Void> original) {
        if((Object)this instanceof ItemFrame frame) ItemFrameHistoryCapture.removed(frame,reason,()->original.call(reason));
        else original.call(reason);
    }
}
