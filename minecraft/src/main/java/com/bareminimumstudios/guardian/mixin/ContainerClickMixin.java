package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.PlayerContainerCapture;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** This invocation occurs after vanilla validates the menu and schedules the packet on the server. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ContainerClickMixin {
    @Redirect(method = "handleContainerClick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;clicked(IILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)V"))
    private void guardian$captureClick(AbstractContainerMenu menu, int slot, int button, ClickType type, Player player) {
        var pending = PlayerContainerCapture.begin(menu, type, player);
        // An exception must not manufacture a successful transaction or leave a pending global frame.
        menu.clicked(slot, button, type, player);
        PlayerContainerCapture.finish(pending, menu, player);
    }
}
