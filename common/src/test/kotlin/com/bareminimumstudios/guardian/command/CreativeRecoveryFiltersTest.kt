package com.bareminimumstudios.guardian.command

import kotlin.test.*

class CreativeRecoveryFiltersTest {
    @Test fun requiresExactlyOneIncludedPlayerAndTime() {
        val f=CreativeRecoveryFilters.parse("u:Alex t:10m").getOrThrow();assertEquals("Alex",f.actorName);assertEquals(600_000L,f.lookbackMillis)
        for(raw in listOf("t:10m","u:Alex","u:Alex,Bob t:10m","u:!Alex t:10m","u:Alex t:10m r:5","u:Alex t:10m i:coal"))assertTrue(CreativeRecoveryFilters.parse(raw).isFailure,raw)
    }
    @Test fun offersPlayersAndTimeWithoutMisleadingRegionFilters() {
        assertContains(CreativeRecoveryFilters.suggestions("u:A",listOf("Alex","Bob")),"u:Alex")
        assertTrue(CreativeRecoveryFilters.suggestions("",listOf("Alex")).none { it.startsWith("r:") || it.startsWith("i:") })
    }
}
