package com.bareminimumstudios.guardian.integration.api

import net.minecraft.server.network.ServerPlayerEntity

fun interface RegionSelectionProvider {
    fun selectionFor(player: ServerPlayerEntity): RegionSelectionResult
}
