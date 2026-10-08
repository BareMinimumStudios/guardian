package com.bareminimumstudios.guardian.command
import kotlin.test.*
class FilterSuggestionsTest {
    @Test fun preservesAliasesAndCompletesPlayerNames() {
        assertEquals(listOf("user:poke0"), FilterSuggestions.values("user:po", listOf("poke0", "Alex")))
        assertEquals(listOf("u:Alex"), FilterSuggestions.values("u:a", listOf("poke0", "Alex")))
        assertTrue("time:" in FilterSuggestions.values("ti", emptyList()))
    }
    @Test fun completesTypedTimeAmountAndHidesBlockActionsForItems() {
        assertEquals(listOf("t:12s", "t:12m", "t:12h", "t:12d", "t:12w"), FilterSuggestions.values("t:12", emptyList()))
        assertTrue(FilterSuggestions.values("", emptyList(), true).none { it == "a:" || it == "action:" })
        assertEquals(listOf("a:break", "a:block"), FilterSuggestions.values("a:b", emptyList()))
    }
    @Test fun pageAliasesRejectDuplicateAndNonpositivePages() {
        assertEquals(2, BlockCommandFilterParser.parse("user:poke0 page:2 limit:20").getOrThrow().page)
        assertTrue(BlockCommandFilterParser.parse("p:0").isFailure)
        assertTrue(BlockCommandFilterParser.parse("p:1 page:2").isFailure)
    }

}
