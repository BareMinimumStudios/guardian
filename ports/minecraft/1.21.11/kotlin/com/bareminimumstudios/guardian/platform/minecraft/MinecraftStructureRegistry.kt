package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.ResourceId
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.structure.Structure
import java.util.IdentityHashMap

internal object MinecraftStructureRegistry {
    fun selected(level:ServerLevel,ids:Set<ResourceId>):Map<Structure,ResourceId> {
        val result=IdentityHashMap<Structure,ResourceId>()
        level.registryAccess().lookupOrThrow(Registries.STRUCTURE).listElements().forEach { holder ->
            val id=ResourceId.parse(holder.key().identifier().toString())
            if(id in ids)result[holder.value()]=id
        }
        require(result.values.toSet()==ids) { "Unknown configured structure IDs: "+(ids-result.values.toSet()).joinToString() }
        return result
    }
}
