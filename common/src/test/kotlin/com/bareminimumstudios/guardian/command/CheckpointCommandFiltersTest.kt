package com.bareminimumstudios.guardian.command

import kotlin.test.*

class CheckpointCommandFiltersTest {
    @Test fun filtersAreOptionalAndCaptureRequiresOneExplicitPlayer() {
        assertNull(CheckpointCommandFilters.parse("").getOrThrow().actorName)
        val filters=CheckpointCommandFilters.parse("u:Alex t:1h l:10 p:2").getOrThrow()
        assertEquals("Alex",filters.actorName);assertEquals(3_600_000L,filters.lookbackMillis);assertEquals(2,filters.page)
        assertEquals("Alex",CheckpointCommandFilters.capture("user:Alex").getOrThrow())
        assertTrue(CheckpointCommandFilters.capture("").isFailure)
        assertTrue(CheckpointCommandFilters.capture("u:Alex t:1h").isFailure)
    }
    @Test fun unrelatedScopesAndUnboundedPagesAreRefused() {
        for(raw in listOf("r:5","i:minecraft:coal","u:Alex,Bob","l:51","p:2001"))
            assertTrue(CheckpointCommandFilters.parse(raw).isFailure,raw)
    }
}
