package com.bareminimumstudios.guardian.lookup
import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import kotlin.test.*
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
class ActivityFormatterTest {
    companion object { init { SharedConstants.tryDetectVersion();Bootstrap.bootStrap() } }
    private fun row(text:String)=PlayerActivity(timestampEpochMillis=1000,actor=ActorIdentity.Player(UUID.randomUUID(),"Alice"),kind=PlayerActivityKind.WHISPER,text=text,recipientUuid=UUID.randomUUID(),recipientName="Bob")
    @Test fun shortRowsShowAgeParticipantsAndHighlightedMatchesWithExactHover() {
        val r=row("hello TEST world");val line=ActivityFormatter.line(r,3000,needle="test")
        assertTrue("2s ago" in line.string && "Alice → Bob" in line.string)
        assertTrue(line.siblings.any { it.string=="TEST" && it.style.isBold })
        val hover=line.style.hoverEvent!!.getValue(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT)!!.string
        assertTrue(r.eventId.toString() in hover && "hello TEST world" in hover)
    }
    @Test fun consoleKeepsFullTextWithoutHover() {
        val text="x".repeat(4096);val line=ActivityFormatter.console(row(text))
        assertTrue(line.string.endsWith(text));assertTrue("1970-01-01" in line.string)
    }
    @Test fun longTextIsSafeLiteralPreviewAndFullTextStaysOnHover() {
        val line=ActivityFormatter.line(row("😀".repeat(200)+" click run_command "+'§'+"c"),spy=true)
        assertTrue(line.string.startsWith("[Guardian spy]"));assertTrue("hover for full text" in line.string)
        assertTrue(line.string.codePointCount(0,line.string.length)<260)
        assertNull(line.style.clickEvent)
        assertTrue(line.siblings.all { it.style.clickEvent==null })
    }
}
