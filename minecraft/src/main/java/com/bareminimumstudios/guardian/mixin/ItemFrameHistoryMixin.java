package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.ItemFrameHistoryCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemFrame.class)
public abstract class ItemFrameHistoryMixin {
    @WrapMethod(method = "interact")
    private InteractionResult guardian$display(Player player, InteractionHand hand, Operation<InteractionResult> original) {
        ItemFrameHistoryCapture.Pending before=ItemFrameHistoryCapture.begin((ItemFrame)(Object)this,player);
        InteractionResult result=original.call(player,hand);
        ItemFrameHistoryCapture.changed(before,result.consumesAction());
        return result;
    }
    @WrapMethod(method = "hurt")
    private boolean guardian$damage(DamageSource source,float amount,Operation<Boolean> original) {
        return ItemFrameHistoryCapture.hurt((ItemFrame)(Object)this,source,()->original.call(source,amount));
    }
}
