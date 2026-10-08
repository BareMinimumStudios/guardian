package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;
import java.util.OptionalInt;

@Mixin(ServerPlayer.class)
public abstract class ContainerOpenMixin {
    @WrapMethod(method = "openMenu(Lnet/minecraft/world/MenuProvider;)Ljava/util/OptionalInt;")
    private OptionalInt guardian$guardOpen(MenuProvider provider, Operation<OptionalInt> original) {
        if (!MinecraftInventoryCoordination.allowsOpen((ServerPlayer)(Object)this, provider)) return OptionalInt.empty();
        return original.call(provider);
    }
}
