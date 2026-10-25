package com.bareminimumstudios.guardian.platform.minecraft

import net.minecraft.world.level.block.entity.LecternBlockEntity

/** Attached by the native lectern menu factory, never resolved by searching nearby blocks. */
interface LecternMenuOwner {
    fun guardianLectern():LecternBlockEntity?
    fun guardianAttachLectern(owner:LecternBlockEntity)
}
