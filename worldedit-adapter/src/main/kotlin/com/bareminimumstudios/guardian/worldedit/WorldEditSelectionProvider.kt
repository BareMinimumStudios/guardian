package com.bareminimumstudios.guardian.worldedit

import com.bareminimumstudios.guardian.domain.BlockBounds
import com.bareminimumstudios.guardian.domain.BlockPosition
import com.bareminimumstudios.guardian.domain.ResourceId
import com.bareminimumstudios.guardian.integration.api.RegionSelection
import com.bareminimumstudios.guardian.integration.api.RegionSelectionProvider
import com.bareminimumstudios.guardian.integration.api.RegionSelectionResult
import com.sk89q.worldedit.IncompleteRegionException
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.fabric.FabricAdapter
import com.sk89q.worldedit.regions.CuboidRegion
import net.minecraft.server.level.ServerPlayer

class WorldEditSelectionProvider : RegionSelectionProvider {
    override fun selectionFor(player: ServerPlayer): RegionSelectionResult {
        return runCatching {
            val actor = FabricAdapter.adaptPlayer(player)
            val world = FabricAdapter.adapt(player.serverLevel())
            val session = WorldEdit.getInstance().sessionManager.get(actor)
            val region = try {
                session.getSelection(world)
            } catch (_: IncompleteRegionException) {
                return RegionSelectionResult.Failure("WorldEdit selection is incomplete. Set both selection positions first.")
            }

            if (region !is CuboidRegion) {
                return RegionSelectionResult.Failure(
                    "Guardian Step 3 only accepts cuboid WorldEdit selections for r:#worldedit; " +
                        "non-cuboid regions are rejected rather than approximated unsafely."
                )
            }

            val min = region.minimumPoint
            val max = region.maximumPoint
            RegionSelectionResult.Success(
                RegionSelection(
                    providerId = "worldedit",
                    dimension = ResourceId.parse(player.serverLevel().dimension().location().toString()),
                    bounds = BlockBounds(
                        BlockPosition(min.x(), min.y(), min.z()),
                        BlockPosition(max.x(), max.y(), max.z())
                    )
                )
            )
        }.getOrElse {
            RegionSelectionResult.Failure("Unable to read the current WorldEdit selection: ${it.message}")
        }
    }
}
