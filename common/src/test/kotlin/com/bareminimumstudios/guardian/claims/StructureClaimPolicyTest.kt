package com.bareminimumstudios.guardian.claims

import com.bareminimumstudios.guardian.domain.ResourceId
import kotlin.test.*

class StructureClaimPolicyTest {
    private val city = ResourceId.parse("minecraft:ancient_city")
    private val player = StructureClaimRequester(false, false, false)

    @Test fun `buffer includes chunk edges and the whole structure rather than its center`() {
        val footprint = StructureClaimFootprint(city, 0, 0, 200, 200)
        assertTrue(footprint.intersectsChunk(15, 5, 50)) // nearest block x=240, 40 from footprint
        assertFalse(footprint.intersectsChunk(16, 5, 50))
        assertTrue(footprint.intersectsChunk(0, 0, 0))
        assertTrue(StructureClaimFootprint(city, -100, -100, -1, -1).intersectsChunk(-1, -1, 0))
        assertFalse(footprint.intersectsChunk(Int.MAX_VALUE, Int.MIN_VALUE, 4096))
    }

    @Test fun `buffer is a radius at corners rather than a larger enclosing square`() {
        val point = StructureClaimFootprint(city, 0, 0, 0, 0)
        assertTrue(point.intersectsChunk(3, 0, 50))
        assertFalse(point.intersectsChunk(3, 3, 50))
        assertTrue(point.intersectsChunk(0, -4, 50)) // nearest z=-49
        assertFalse(point.intersectsChunk(0, -5, 50))
    }

    @Test fun `staff must use both admin mode and server ownership`() {
        val policy = StructureClaimPolicy(setOf(city))
        val footprint = listOf(StructureClaimFootprint(city, 0, 0, 31, 31))
        for (staff in listOf(false, true)) for (admin in listOf(false, true)) for (server in listOf(false, true)) {
            val result = policy.check(StructureClaimRequester(staff, admin, server), 0, 0, footprint, true)
            assertEquals(if (staff && admin && server) StructureClaimDecision.PASS else StructureClaimDecision.PROTECTED_STRUCTURE, result)
        }
    }

    @Test fun `unknown lookups never masquerade as absence of structures`() {
        val policy = StructureClaimPolicy(setOf(city))
        assertEquals(StructureClaimDecision.LOOKUP_PENDING, policy.check(player, 0, 0, emptyList(), false))
        assertEquals(StructureClaimDecision.PASS, policy.check(player, 0, 0, emptyList(), true))
        val other = StructureClaimFootprint(ResourceId.parse("minecraft:village_plains"), 0, 0, 31, 31)
        assertEquals(StructureClaimDecision.PASS, policy.check(player, 0, 0, listOf(other), true))
        assertFailsWith<IllegalArgumentException> { StructureClaimPolicy(setOf(city), 4097) }
    }

    @Test fun `configured ids are immutable after admission`() {
        val input = mutableSetOf(city)
        val policy = StructureClaimPolicy(input)
        input.clear()
        assertEquals(setOf(city), policy.structures)
    }
}
