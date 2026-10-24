package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.EntityHistoryCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Entity.class)
public abstract class EntityNameHistoryMixin {
    @WrapMethod(method = "setCustomName")
    private void guardian$name(Component name, Operation<Void> original) {
        Entity entity = (Entity)(Object)this;
        String before = EntityHistoryCapture.plainName(entity);
        original.call(name);
        EntityHistoryCapture.renamed(entity,before);
    }
}
