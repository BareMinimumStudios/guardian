package com.bareminimumstudios.guardian.command
import com.bareminimumstudios.guardian.domain.*
import java.util.UUID
import kotlin.test.*
class SpyFilterTest {
    private val row=PlayerActivity(timestampEpochMillis=1,actor=ActorIdentity.Player(UUID.randomUUID(),"Alice"),kind=PlayerActivityKind.WHISPER,text="hello",recipientUuid=UUID.randomUUID(),recipientName="Bob")
    @Test fun defaultsIncludeEveryoneAndRecipientFiltersWork() {
        assertTrue(SpyFilter.parse("",false).matches(row))
        assertTrue(SpyFilter.parse("u:bob",false).matches(row))
        assertFalse(SpyFilter.parse("u:alice,!bob",false).matches(row))
        assertTrue(SpyFilter.parse("u:"+row.actor.uuid,false).matches(row))
    }
    @Test fun commandRootsAreNormalizedAndExclusionsWin() {
        val cmd=row.copy(kind=PlayerActivityKind.COMMAND,recipientUuid=null,recipientName=null,text="/minecraft:say hello")
        assertTrue(SpyFilter.parse("c:say u:Alice",true).matches(cmd))
        assertFalse(SpyFilter.parse("c:say,!say",true).matches(cmd))
        assertFailsWith<IllegalArgumentException> { SpyFilter.parse("c:help",false) }
        assertFailsWith<IllegalArgumentException> { SpyFilter.parse("u:Alice u:Bob",true) }
        assertFailsWith<IllegalArgumentException> { SpyFilter.parse("u:",true) }
    }
    @Test fun quotedLiteralTextAndNavigationKeepTheirMeaning() {
        val f=ActivityTextFilter.parse("u:Alice q:\"p:2 o:oldest test\" t:1h")
        assertEquals("p:2 o:oldest test",f.text)
        assertEquals("u:Alice t:1h",f.remaining)
        val nav=HistoryPageCommands.command("whispers","q:\"p:2 o:oldest test\" t:1h p:1",2,false)
        assertTrue("q:\"p:2 o:oldest test\"" in nav)
        assertEquals(f.text,ActivityTextFilter.parse(nav.substringAfter("whispers ")).text)
        for(raw in listOf("q:x","q:\"\"","q:\"open","q:abc q:def"))assertFailsWith<IllegalArgumentException> { ActivityTextFilter.parse(raw) }
    }
}
