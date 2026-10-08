package com.bareminimumstudios.guardian.command
import kotlin.test.*
class ItemRollbackPreviewFiltersTest {
    @Test fun requiresTimeAndRejectsIgnoredFilters() {
        for(raw in listOf("r:10","t:1h l:20","t:1h p:2","t:1h a:block","t:1h o:newest","t:1h x:1")) assertTrue(ItemRollbackPreviewFilters.parse(raw).isFailure,raw)
    }
    @Test fun acceptsSharedUserTimeCoordinatesAndWorldEditSyntax() {
        val filter=ItemRollbackPreviewFilters.parse("user:Tester time:2h radius:0 x:1 y:64 z:2").getOrThrow()
        assertEquals("Tester",filter.actorName);assertEquals(7200000L,filter.lookbackMillis);assertEquals(0,filter.radius)
        assertTrue(ItemRollbackPreviewFilters.parse("t:1d r:#we").getOrThrow().useWorldEditSelection)
    }
    @Test fun completionOffersSupportedKeysPlayersAndUnitsOnly() {
        assertEquals(listOf("t:15s","t:15m","t:15h","t:15d","t:15w"),ItemRollbackPreviewFilters.suggestions("t:15",emptyList()))
        assertEquals(listOf("u:Tester"),ItemRollbackPreviewFilters.suggestions("u:Te",listOf("Tester")))
        assertFalse(ItemRollbackPreviewFilters.suggestions("",emptyList()).any{it in listOf("p:","l:","o:","a:")})
    }
}
