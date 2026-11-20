package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.ResourceId
import net.minecraft.server.level.ServerLevel

internal object MinecraftDimensionId {
    fun of(level: ServerLevel): ResourceId = ResourceId.parse(level.dimension().identifier().toString())
}
