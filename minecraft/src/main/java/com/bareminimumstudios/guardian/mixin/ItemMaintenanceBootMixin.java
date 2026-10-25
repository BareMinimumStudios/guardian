package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.ItemMaintenanceRecovery;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(MinecraftServer.class)
public abstract class ItemMaintenanceBootMixin {
    @Shadow @Final protected LevelStorageSource.LevelStorageAccess storageSource;
    @Shadow private boolean stopped;

    @WrapMethod(method = "runServer")
    private void guardian$maintenanceBoot(Operation<Void> original) {
        if (!ItemMaintenanceRecovery.requested()) { original.call(); return; }
        MinecraftServer server = (MinecraftServer) (Object) this;
        int result;
        try {
            result = ItemMaintenanceRecovery.run(server, storageSource,
                ((ItemRecoveryStorageAccessor) storageSource).guardian$itemRecoveryLock());
        } finally {
            stopped = true;
            server.halt(false);
            storageSource.safeClose();
            server.onServerExit();
        }
        // Exit from a separate thread after the server thread returns: vanilla's shutdown hook
        // joins the server thread and would deadlock if System.exit ran on that same thread.
        Thread owner = Thread.currentThread();
        int code = result;
        Thread exit = new Thread(() -> {
            try { owner.join(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
            System.exit(code);
        }, "Guardian-item-recovery-exit");
        exit.setDaemon(false);
        exit.start();
    }
}
