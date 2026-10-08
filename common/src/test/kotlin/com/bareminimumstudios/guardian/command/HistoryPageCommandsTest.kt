package com.bareminimumstudios.guardian.command
import kotlin.test.*
class HistoryPageCommandsTest {
    @Test fun navigationPreservesFiltersAndReplacesPageAndOrder() {
        val command = HistoryPageCommands.command("transactions", "user:Cherry time:1h limit:5 page:9 order:newest", 2, true)
        val parsed = BlockCommandFilterParser.parse(command.substringAfter("transactions ")).getOrThrow()
        assertEquals("Cherry", parsed.actorName); assertEquals(3600000, parsed.lookbackMillis)
        assertEquals(5, parsed.limit); assertEquals(2, parsed.page); assertTrue(parsed.oldestFirst)
    }
    @Test fun rejectsInvalidOrdersAndDuplicateAliases() {
        assertTrue(BlockCommandFilterParser.parse("o:sideways").isFailure)
        assertTrue(BlockCommandFilterParser.parse("order:oldest o:newest").isFailure)
        assertTrue("order:oldest" in FilterSuggestions.values("order:o", emptyList()))
    }
}
