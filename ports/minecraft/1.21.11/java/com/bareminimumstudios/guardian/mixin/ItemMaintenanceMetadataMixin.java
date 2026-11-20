package com.bareminimumstudios.guardian.mixin;
import com.bareminimumstudios.guardian.platform.minecraft.ItemMaintenanceRecovery;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.Main;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Maintenance preview must not refresh level.dat during the launcher bootstrap. */
@Mixin(Main.class)
public abstract class ItemMaintenanceMetadataMixin {
    @WrapOperation(method = "main", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;saveDataTag(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/storage/WorldData;)V"))
    private static void guardian$maintenanceMetadata(LevelStorageSource.LevelStorageAccess access,
            RegistryAccess registries, WorldData data, Operation<Void> original) {
        if (!ItemMaintenanceRecovery.requested()) original.call(access, registries, data);
    }
}
