package com.bareminimumstudios.guardian.mixin;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(CraftingMenu.class)
public interface CraftingMenuAccessor {
    @Accessor("access") ContainerLevelAccess guardian$access();
}
