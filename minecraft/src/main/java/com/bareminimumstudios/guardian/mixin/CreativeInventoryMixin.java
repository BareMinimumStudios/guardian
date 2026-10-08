package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.PlayerContainerCapture;
import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Vanilla reaches this invocation only after scheduling and creative/slot/item validation. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class CreativeInventoryMixin {
    @Shadow public ServerPlayer player;

    @WrapMethod(method = "handleSetCreativeModeSlot")
    private void guardian$guardCreative(ServerboundSetCreativeModeSlotPacket packet, Operation<Void> original) {
        if (!player.server.isSameThread()) { original.call(packet); return; }
        if (!MinecraftInventoryCoordination.allowsPlayerMutation(player)) {
            MinecraftInventoryCoordination.resynchronize(player);
            return;
        }
        original.call(packet);
    }

    @WrapOperation(method = "handleSetCreativeModeSlot", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;setByPlayer(Lnet/minecraft/world/item/ItemStack;)V"))
    private void guardian$captureCreativeSet(Slot slot, ItemStack stack, Operation<Void> original) {
        try (var pending = PlayerContainerCapture.beginCreative(slot, player)) {
            original.call(slot, stack);
            PlayerContainerCapture.finish(pending, player);
        }
    }
}
