package com.bareminimumstudios.guardian.mixin;

import net.minecraft.world.Container;
import net.minecraft.world.CompoundContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CompoundContainer.class)
public interface CompoundContainerAccessor {
    @Accessor("container1") Container guardian$first();
    @Accessor("container2") Container guardian$second();
}
