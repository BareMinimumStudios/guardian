package com.bareminimumstudios.guardian.mixin;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(Inventory.class)
public interface OwnedPlayerInventoryAccessor {
    @Mutable @Accessor("items") void guardian$setItems(NonNullList<ItemStack> items);
    @Accessor("equipment") EntityEquipment guardian$getEquipment();
    @Mutable @Accessor("equipment") void guardian$setEquipment(EntityEquipment equipment);
}
