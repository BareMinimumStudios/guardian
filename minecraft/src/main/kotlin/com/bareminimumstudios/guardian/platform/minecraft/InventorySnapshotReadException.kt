package com.bareminimumstudios.guardian.platform.minecraft

/** Contains only the slot number and error type, never item NBT or component contents. */
internal class InventorySnapshotReadException(val diagnostic:String,cause:Exception):IllegalStateException(diagnostic,cause)
