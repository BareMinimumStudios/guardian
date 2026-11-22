package com.bareminimumstudios.guardian.platform.minecraft

import net.minecraft.nbt.ByteTag
import net.minecraft.nbt.CompoundTag

/** Version-specific primitive access; all equipment paths and validation stay shared. */
internal object EquipmentSavedNbtAccess {
    fun keys(tag: CompoundTag): Set<String> = tag.allKeys
    fun string(tag: CompoundTag, key: String): String = tag.getString(key)
    fun integer(tag: CompoundTag, key: String): Int = tag.getInt(key)
    fun byte(tag: CompoundTag, key: String): Byte {
        require(tag.get(key) is ByteTag)
        return tag.getByte(key)
    }
}
