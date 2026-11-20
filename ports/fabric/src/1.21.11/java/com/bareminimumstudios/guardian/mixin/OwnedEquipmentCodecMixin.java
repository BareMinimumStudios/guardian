package com.bareminimumstudios.guardian.mixin;
import java.util.Map;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import com.bareminimumstudios.guardian.platform.minecraft.OwnedEquipmentSnapshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(EntityEquipment.class)
public abstract class OwnedEquipmentCodecMixin {
    @Inject(method="method_66658",remap=false,at=@At("HEAD"),cancellable=true,require=1)
    private static void guardian$encodedImage(EntityEquipment equipment, CallbackInfoReturnable<Map<EquipmentSlot,ItemStack>> cir) {
        if(equipment instanceof OwnedEquipmentSnapshot owned) cir.setReturnValue(owned.guardian$snapshotEquipment());
    }
}
