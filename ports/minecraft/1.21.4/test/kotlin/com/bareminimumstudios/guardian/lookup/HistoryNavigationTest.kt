package com.bareminimumstudios.guardian.lookup
import kotlin.test.*
class HistoryNavigationTest {
    @Test fun navigationButtonsKeepExecutableCommandsAndHideUnavailableDirections() {
        val first = HistoryNavigation.footer(1, true, false, { "/guardian inspect page $it" }, "/guardian inspect order oldest")
        assertFalse("Previous" in first.string); assertTrue("Next" in first.string)
        val commands = first.siblings.mapNotNull { it.style.clickEvent?.value }
        assertTrue("/guardian inspect page 2" in commands)
        assertTrue("/guardian inspect order oldest" in commands)
        val empty = HistoryNavigation.footer(2, false, true, { "/guardian inspect page $it" }, "/guardian inspect order newest")
        assertTrue("Previous" in empty.string); assertFalse("Next" in empty.string)
    }
}
