package com.bareminimumstudios.guardian.command

import kotlin.test.*
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.query.*
import java.util.UUID

class HistorySearchFilterTest {
    @Test fun parsesMultiUserAndResourceAliasesWithoutLosingNamespace() {
        val f = BlockCommandFilterParser.parse("u:Alex,Bob,!Charlie include:stone,example:slate e:dirt t:1h").getOrThrow()
        assertNull(f.actorName);assertEquals(listOf("Alex","Bob"), f.search.players.map { it.name })
        assertEquals(listOf("Charlie"), f.search.excludedPlayers.map { it.name })
        assertEquals(setOf(ResourceId.parse("stone"),ResourceId.parse("example:slate")),f.search.included)
        assertTrue(f.search.extended)
    }
    @Test fun rejectsMalformedDuplicateAndExcessiveLists() {
        for (raw in listOf("u:Alex,", "u:!", "u:Alex,alex", "u:!Alex user:Bob", "i:coal,", "i:coal,minecraft:coal", "i:stone include:dirt", "e:stone exclude:dirt", "i:' OR 1=1 --", "i:bad#id", "u:" + (1..33).joinToString(",") { "P$it" })) {
            assertTrue(BlockCommandFilterParser.parse(raw).isFailure, raw)
        }
    }
    @Test fun exclusionWinsAndUnknownActorsSurvivePlayerExclusions() {
        val id=UUID.randomUUID();val player=ActorIdentity.Player(id,"Alex")
        val f=BlockCommandFilterParser.parse("u:Alex,!Alex").getOrThrow().search
        assertFalse(f.matchesActor(player))
        val excluded=BlockCommandFilterParser.parse("u:!Alex").getOrThrow().search
        assertTrue(excluded.matchesActor(ActorIdentity.Unknown));assertTrue(excluded.matchesActor(ActorIdentity.System("hopper")))
        val resolved=HistorySearchFilter(players=listOf(HistoryPlayer("Alex"))).resolve(mapOf("aLeX" to id))
        assertTrue(resolved.matchesActor(ActorIdentity.Player(id,"OldName")))
        assertFalse(resolved.matchesActor(ActorIdentity.Player(UUID.randomUUID(),"Alex")))
    }
    @Test fun completionPreservesCommaPrefixNegationAndNamespaces() {
        assertEquals(listOf("u:Alex,!Bob"),FilterSuggestions.values("u:Alex,!B",listOf("Alex","Bob")))
        assertEquals(listOf("i:minecraft:coal,example:slate"),FilterSuggestions.values("i:minecraft:coal,example:s",emptyList(),true,listOf("minecraft:coal","example:slate")))
        assertEquals(listOf("e:minecraft:coal"),FilterSuggestions.values("e:coa",emptyList(),true,listOf("minecraft:coal")))
        assertTrue(FilterSuggestions.values("",emptyList(),extended=false).none { it=="i:"||it=="e:" })
    }
    @Test fun previewRejectsHistoryExtensionsInsteadOfIgnoringThem() {
        for (raw in listOf("u:Alex,Bob t:1h", "u:!Alex t:1h", "i:coal t:1h", "e:coal t:1h")) assertTrue(ItemRollbackPreviewFilters.parse(raw).isFailure,raw)
        assertEquals("Alex",ItemRollbackPreviewFilters.parse("u:Alex t:1h").getOrThrow().actorName)
        val command=HistoryPageCommands.command("transactions","u:Alex,Bob,!Charlie i:coal e:diamond t:1h p:1",2,true)
        val parsed=BlockCommandFilterParser.parse(command.substringAfter("transactions ")).getOrThrow()
        assertEquals(2,parsed.page);assertEquals(2,parsed.search.players.size);assertTrue(parsed.oldestFirst)
    }
}
