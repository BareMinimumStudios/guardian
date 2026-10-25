package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.LecternMenuOwner;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LecternBlockEntity.class)
public abstract class LecternMenuFactoryMixin {
    @WrapMethod(method="createMenu")
    private AbstractContainerMenu guardian$attach(int id,Inventory inventory,Player player,Operation<AbstractContainerMenu> original) {
        var menu=original.call(id,inventory,player);
        if(menu instanceof LecternMenuOwner owner)owner.guardianAttachLectern((LecternBlockEntity)(Object)this);
        return menu;
    }
}
