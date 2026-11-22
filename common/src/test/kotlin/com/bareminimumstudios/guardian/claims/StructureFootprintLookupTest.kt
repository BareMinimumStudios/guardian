package com.bareminimumstudios.guardian.claims

import com.bareminimumstudios.guardian.domain.ResourceId
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class StructureFootprintLookupTest {
    private val world=ResourceId.parse("minecraft:overworld")
    private val structure=ResourceId.parse("minecraft:trial_chambers")
    private val player=StructureClaimRequester(false,false,false)
    private val empty=StructureChunkMetadata(emptyList(),emptySet(),true)
    private class Source:StructureChunkSource {
        val live=mutableMapOf<StructureChunkKey,StructureChunkMetadata>()
        val reads=mutableListOf<Pair<StructureChunkKey,CompletableFuture<StructureChunkMetadata>>>()
        var onLoaded:()->Unit={}
        override fun loaded(key:StructureChunkKey):StructureChunkMetadata? {onLoaded();return live[key]}
        override fun stored(key:StructureChunkKey)=CompletableFuture<StructureChunkMetadata>().also { reads.add(key to it) }
    }
    private fun key(x:Int=0,z:Int=0)=StructureChunkKey(world,x,z)
    private fun lookup(source:Source,buffer:Int=0,clock:()->Long={0},cached:Int=4096,queued:Int=2048)=
        StructureFootprintLookup(StructureClaimPolicy(setOf(structure),buffer),source,clock,cached,queued)

    @Test fun unknownDataCannotAuthorizeAClaimAndReadsAreNonblockingAndBounded() {
        val source=Source();val lookup=lookup(source,50)
        assertEquals(StructureClaimDecision.LOOKUP_PENDING,lookup.check(player,world,0,0))
        assertEquals(81,lookup.queuedChunks);assertTrue(source.reads.isEmpty())
        lookup.tick();assertEquals(4,source.reads.size);assertEquals(4,lookup.readingChunks)
        repeat(20){lookup.tick()};assertEquals(4,source.reads.size)
        source.reads.forEach { it.second.complete(empty) };lookup.tick()
        assertEquals(8,source.reads.size);assertTrue(lookup.cachedChunks<=4)
    }
    @Test fun referencesResolveTheWholeStructureBoxBeyondTheStartChunk() {
        val source=Source();val origin=key(-6)
        source.live[key()]=StructureChunkMetadata(emptyList(),setOf(origin),true)
        val lookup=lookup(source)
        assertEquals(StructureClaimDecision.LOOKUP_PENDING,lookup.check(player,world,0,0))
        lookup.tick();assertEquals(listOf(origin),source.reads.map { it.first })
        source.reads.single().second.complete(StructureChunkMetadata(listOf(StructureClaimFootprint(structure,-100,-4,12,12)),emptySet(),true))
        lookup.tick();assertEquals(StructureClaimDecision.PROTECTED_STRUCTURE,lookup.check(player,world,0,0))
    }
    @Test fun aPositiveFootprintProtectsEvenWhenOtherDataIsPending() {
        val source=Source();source.live[key(-4,-4)]=StructureChunkMetadata(listOf(StructureClaimFootprint(structure,0,0,15,15)),emptySet(),false)
        assertEquals(StructureClaimDecision.PROTECTED_STRUCTURE,lookup(source,50).check(player,world,0,0))
    }
    @Test fun aStaleReadCannotReauthorizeAfterAConcurrentStructureChange() {
        val source=Source();val lookup=lookup(source)
        assertEquals(StructureClaimDecision.LOOKUP_PENDING,lookup.check(player,world,0,0));lookup.tick()
        CompletableFuture.runAsync { lookup.invalidateChunk(0,0) }.join()
        source.reads.single().second.complete(empty);lookup.tick()
        assertEquals(0,lookup.cachedChunks)
        assertEquals(StructureClaimDecision.LOOKUP_PENDING,lookup.check(player,world,0,0));lookup.tick()
        assertEquals(2,source.reads.size)
        source.reads.last().second.complete(StructureChunkMetadata(listOf(StructureClaimFootprint(structure,0,0,15,15)),emptySet(),true));lookup.tick()
        assertEquals(StructureClaimDecision.PROTECTED_STRUCTURE,lookup.check(player,world,0,0))
    }
    @Test fun invalidationDuringAQueryRefusesItsOtherwiseCompleteAbsenceProof() {
        val source=Source();source.live[key()]=empty;val lookup=lookup(source)
        source.onLoaded={lookup.invalidateChunk(0,0)}
        assertEquals(StructureClaimDecision.LOOKUP_PENDING,lookup.check(player,world,0,0))
    }
    @Test fun onlyStaffInBothAdminAndServerModeBypassesLookup() {
        for(staff in listOf(false,true))for(admin in listOf(false,true))for(server in listOf(false,true)) {
            val source=Source();val lookup=lookup(source)
            val result=lookup.check(StructureClaimRequester(staff,admin,server),world,0,0)
            assertEquals(if(staff&&admin&&server)StructureClaimDecision.PASS else StructureClaimDecision.LOOKUP_PENDING,result)
            assertEquals(if(staff&&admin&&server)0 else 1,lookup.queuedChunks)
        }
    }
    @Test fun incompleteMetadataCooldownAndClockWrapDoNotBecomeSuccessfulAbsence() {
        val source=Source();var now=Long.MAX_VALUE-100;val lookup=lookup(source,clock={now})
        lookup.check(player,world,0,0);lookup.tick()
        source.reads.single().second.complete(StructureChunkMetadata(emptyList(),emptySet(),false));lookup.tick()
        now+=200
        assertEquals(StructureClaimDecision.LOOKUP_PENDING,lookup.check(player,world,0,0));lookup.tick();assertEquals(1,source.reads.size)
        now+=1_000_000_000
        lookup.check(player,world,0,0);lookup.tick();assertEquals(2,source.reads.size)
    }
    @Test fun queueOverflowAndInvalidCoordinatesFailClosedWithoutUnboundedReads() {
        val source=Source();val lookup=lookup(source,50,queued=2)
        assertEquals(StructureClaimDecision.LOOKUP_PENDING,lookup.check(player,world,0,0));assertEquals(2,lookup.queuedChunks)
        assertEquals(StructureClaimDecision.LOOKUP_PENDING,lookup.check(player,world,Int.MAX_VALUE,Int.MIN_VALUE));assertEquals(2,lookup.queuedChunks)
        lookup.close();assertEquals(0,lookup.queuedChunks);assertFailsWith<IllegalStateException> { lookup.check(player,world,0,0) }
    }
}
