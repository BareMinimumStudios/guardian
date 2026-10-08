package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftInventoryCoordination;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LivingEntity.class)
public abstract class OngoingItemUseMixin {
    @Unique
    private boolean guardian$allowsUse() {
        return !((Object) this instanceof ServerPlayer player)
            || MinecraftInventoryCoordination.allowsAutomation(player.level());
    }

    @WrapMethod(method = "startUsingItem")
    private void guardian$guardStart(InteractionHand hand, Operation<Void> original) {
        if (!guardian$allowsUse()) {
            ServerPlayer player = (ServerPlayer) (Object) this;
            if (player.server.isSameThread()) MinecraftInventoryCoordination.resynchronize(player);
            return;
        }
        original.call(hand);
    }

    @WrapMethod(method = {"updatingUsingItem", "completeUsingItem"})
    private void guardian$guardProgress(Operation<Void> original) {
        if (guardian$allowsUse()) original.call();
    }

    @WrapMethod(method = "updateUsingItem")
    private void guardian$guardTick(ItemStack stack, Operation<Void> original) {
        if (guardian$allowsUse()) original.call(stack);
    }

    @WrapMethod(method = {"releaseUsingItem", "stopUsingItem"})
    private void guardian$beforeCleanup(Operation<Void> original) {
        // Release/stop callbacks can mutate remote inventories. Revoke coordination
        // before running them, rather than keeping a player stuck in an active use.
        if ((Object) this instanceof ServerPlayer player && player.isUsingItem()) {
            MinecraftInventoryCoordination.beforeItemUseCleanup(player);
        }
        original.call();
    }
}
