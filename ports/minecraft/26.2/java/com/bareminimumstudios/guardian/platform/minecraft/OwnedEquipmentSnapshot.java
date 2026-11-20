package com.bareminimumstudios.guardian.platform.minecraft;
import java.util.Map;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
/** Fresh, non-aliasing values for the native equipment disk codec. */
public interface OwnedEquipmentSnapshot { Map<EquipmentSlot,ItemStack> guardian$snapshotEquipment(); }
