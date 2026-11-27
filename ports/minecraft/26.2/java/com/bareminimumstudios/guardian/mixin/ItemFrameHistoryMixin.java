package com.bareminimumstudios.guardian.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
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
    private InteractionResult guardian$display(Player player, InteractionHand hand, Vec3 location, Operation<InteractionResult> original) {
        ItemFrameHistoryCapture.Pending before=ItemFrameHistoryCapture.begin((ItemFrame)(Object)this,player);
        InteractionResult result=original.call(player,hand,location);
        ItemFrameHistoryCapture.changed(before,result.consumesAction());
        return result;
    }
    @WrapMethod(method = "hurtServer")
    private boolean guardian$damage(ServerLevel level,DamageSource source,float amount,Operation<Boolean> original) {
        return ItemFrameHistoryCapture.hurt((ItemFrame)(Object)this,source,()->original.call(level,source,amount));
    }
}
