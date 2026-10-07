package com.bareminimumstudios.guardian.integration.api

import net.minecraft.server.level.ServerPlayer

fun interface RegionSelectionProvider {
    fun selectionFor(player: ServerPlayer): RegionSelectionResult
}
