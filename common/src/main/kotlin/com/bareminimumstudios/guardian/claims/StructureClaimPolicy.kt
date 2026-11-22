package com.bareminimumstudios.guardian.claims

import com.bareminimumstudios.guardian.domain.ResourceId
import java.util.Collections

/** Horizontal footprint of an actual structure start, including its entire bounding box. */
data class StructureClaimFootprint(
    val structure: ResourceId,
    val minX: Int,
    val minZ: Int,
    val maxX: Int,
    val maxZ: Int
) {
    init { require(minX <= maxX && minZ <= maxZ) }

    /** Claims are whole chunks. Refuse when even one block falls in the protected buffer. */
    fun intersectsChunk(chunkX: Int, chunkZ: Int, bufferBlocks: Int): Boolean {
        require(bufferBlocks in 0..4096)
        val left = chunkX.toLong() * 16
        val top = chunkZ.toLong() * 16
        val dx = maxOf(minX.toLong() - (left + 15), left - maxX, 0L)
        val dz = maxOf(minZ.toLong() - (top + 15), top - maxZ, 0L)
        // Reject distant coordinates before squaring, including arbitrary external API inputs.
        if (dx > bufferBlocks || dz > bufferBlocks) return false
        return dx * dx + dz * dz <= bufferBlocks.toLong() * bufferBlocks
    }
}

/** Captured from verified requester permissions and OPAC mode, never inferred from OP alone. */
data class StructureClaimRequester(val staff: Boolean, val adminMode: Boolean, val serverClaim: Boolean) {
    val mayReserveStructure: Boolean get() = staff && adminMode && serverClaim
}

enum class StructureClaimDecision { PASS, PROTECTED_STRUCTURE, LOOKUP_PENDING }

/** Pure permission policy. PASS preserves all existing OPAC permissions and other addon denials. */
class StructureClaimPolicy(structures: Set<ResourceId>, val bufferBlocks: Int = 50) {
    val structures: Set<ResourceId> = Collections.unmodifiableSet(HashSet(structures))
    init { require(structures.size <= 64 && bufferBlocks in 0..4096) }

    fun check(
        requester: StructureClaimRequester,
        chunkX: Int,
        chunkZ: Int,
        footprints: Collection<StructureClaimFootprint>,
        lookupComplete: Boolean
    ): StructureClaimDecision {
        if (structures.isEmpty() || requester.mayReserveStructure) return StructureClaimDecision.PASS
        if (footprints.any { it.structure in structures && it.intersectsChunk(chunkX, chunkZ, bufferBlocks) })
            return StructureClaimDecision.PROTECTED_STRUCTURE
        return if (lookupComplete) StructureClaimDecision.PASS else StructureClaimDecision.LOOKUP_PENDING
    }
}
