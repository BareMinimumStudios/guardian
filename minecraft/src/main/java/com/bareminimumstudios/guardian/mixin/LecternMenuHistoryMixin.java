package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LecternMenu.class)
public abstract class LecternMenuHistoryMixin implements LecternMenuOwner {
    @Unique private LecternBlockEntity guardian$lectern;
    public LecternBlockEntity guardianLectern() { return guardian$lectern; }
    public void guardianAttachLectern(LecternBlockEntity owner) {
        if(guardian$lectern!=null && guardian$lectern!=owner)throw new IllegalStateException("Lectern menu already attached");
        guardian$lectern=owner;
    }
    @WrapMethod(method="clickMenuButton")
    private boolean guardian$button(Player player,int button,Operation<Boolean> original) {
        if(!(player instanceof ServerPlayer server) || guardian$lectern==null || server.containerMenu!=(Object)this || !server.server.isSameThread())
            return original.call(player,button);
        var before=LecternHistoryCapture.before(server,guardian$lectern.getBlockPos());
        var items=button==3 ? PlayerContainerCapture.beginBlockUse(server.serverLevel(),server,guardian$lectern.getBlockPos()) : null;
        boolean accepted;
        try { accepted=original.call(player,button); }
        catch(Throwable failure) { PlayerContainerCapture.finishBlockUse(server,items,false);throw failure; }
        PlayerContainerCapture.finishBlockUse(server,items,accepted);
        LecternHistoryCapture.after(server,before,accepted,false);
        return accepted;
    }
}
