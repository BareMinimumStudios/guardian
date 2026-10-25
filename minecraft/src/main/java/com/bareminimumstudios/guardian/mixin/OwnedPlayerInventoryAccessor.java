package com.bareminimumstudios.guardian.mixin;

import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Inventory.class)
public interface OwnedPlayerInventoryAccessor {
    @Mutable @Accessor("items") void guardian$setItems(NonNullList<ItemStack> items);
    @Mutable @Accessor("armor") void guardian$setArmor(NonNullList<ItemStack> items);
    @Mutable @Accessor("offhand") void guardian$setOffhand(NonNullList<ItemStack> items);
    @Accessor("compartments") List<NonNullList<ItemStack>> guardian$getCompartments();
    @Mutable @Accessor("compartments") void guardian$setCompartments(List<NonNullList<ItemStack>> items);
}
