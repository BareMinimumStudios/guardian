package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.EquipmentMutationGate;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/** Slot validators and item tags cannot be replaced during a journal-owned equipment handoff. */
@Mixin(MinecraftServer.class)
public abstract class EquipmentResourceReloadMixin {
    @WrapMethod(method="reloadResources")
    private CompletableFuture<Void> guardian$protectEquipmentReload(Collection<String> packs,Operation<CompletableFuture<Void>> original) {
        return EquipmentMutationGate.reloadResources(() -> original.call(packs));
    }
}
