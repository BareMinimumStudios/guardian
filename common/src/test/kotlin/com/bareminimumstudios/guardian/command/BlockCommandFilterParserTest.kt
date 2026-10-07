package com.bareminimumstudios.guardian.command

import com.bareminimumstudios.guardian.domain.ActionType
import com.bareminimumstudios.guardian.domain.BlockPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockCommandFilterParserTest {
    @Test
    fun parsesCoreProtectStyleBlockFilters() {
        val parsed = BlockCommandFilterParser.parse("u:Sayf t:1d12h r:25 a:place,break x:10 y:64 z:-5 l:40").getOrThrow()
        assertEquals("Sayf", parsed.actorName)
        assertEquals(129_600_000L, parsed.lookbackMillis)
        assertEquals(25, parsed.radius)
        assertEquals(BlockPosition(10, 64, -5), parsed.explicitPosition)
        assertEquals(setOf(ActionType.BLOCK_PLACE, ActionType.BLOCK_BREAK), parsed.actions)
        assertEquals(40, parsed.limit)
    }

    @Test
    fun rejectsPartialCoordinates() {
        assertTrue(BlockCommandFilterParser.parse("x:1 y:2").isFailure)
    }

    @Test
    fun parsesWorldEditSelectionAlias() {
        val parsed = BlockCommandFilterParser.parse("t:10m r:#worldedit").getOrThrow()
        assertTrue(parsed.useWorldEditSelection)
        assertEquals(null, parsed.radius)
        assertTrue(BlockCommandFilterParser.parse("r:#we x:1 y:2 z:3").isFailure)
    }

    @Test
    fun supportsCoreProtectStyleBlockActionAliases() {
        assertEquals(setOf(ActionType.BLOCK_PLACE), BlockCommandFilterParser.parse("a:+block").getOrThrow().actions)
        assertEquals(setOf(ActionType.BLOCK_BREAK), BlockCommandFilterParser.parse("a:-block").getOrThrow().actions)
    }
}
