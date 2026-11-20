package com.bareminimumstudios.guardian.mixin;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(LivingEntity.class)
public interface OwnedLivingEquipmentAccessor {
    @Accessor("equipment") EntityEquipment guardian$getEquipment();
    @Mutable @Accessor("equipment") void guardian$setEquipment(EntityEquipment equipment);
}
