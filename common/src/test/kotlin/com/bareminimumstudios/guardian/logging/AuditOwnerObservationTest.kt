package com.bareminimumstudios.guardian.logging

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.StorageBackend
import java.time.Duration
import java.util.UUID
import java.util.concurrent.*
import kotlin.test.*

class AuditOwnerObservationTest {
    private val dimension=ResourceId.parse("minecraft:overworld")
    private val block=ItemSlotOwner.BlockContainer(dimension,BlockPosition(1,64,1))
    private val player=ItemSlotOwner.PlayerInventory(UUID.randomUUID())
    private val item=ItemStackSnapshot(ResourceId.parse("minecraft:coal"),1,BinaryPayload.of(byteArrayOf(1)))
    private fun change(owner: ItemSlotOwner,index: Int=0)=ItemSlotChange(ItemSlotAddress(owner,index),ItemStackSnapshot.EMPTY,item)
    private fun entry(vararg changes: ItemSlotChange)=ContainerAuditEntry(ContainerTransactionSnapshot(UUID.randomUUID(),1,ActorIdentity.Player(player.playerId,"Tester"),0,ContainerAction.PICKUP,changes.toList()))
    private fun blockEntry(owner: ItemSlotOwner.BlockContainer=block)=BlockChangeSnapshot(1,ActorIdentity.Unknown,owner.dimension,owner.position,BlockStateSnapshot(ResourceId.parse("minecraft:air")),BlockStateSnapshot(ResourceId.parse("minecraft:stone")),ChangeCause.PLAYER,ActionType.BLOCK_PLACE)
    private class Backend(val blocked: Boolean=false): StorageBackend {
        override val id="observation-test"
        val entered=CountDownLatch(1);val gate=CountDownLatch(if(blocked) 1 else 0)
        override fun open()=Unit
        override fun append(entries: List<LogEntry>) { entered.countDown();while(gate.count>0) try { check(gate.await(3,TimeUnit.SECONDS)) } catch(_: InterruptedException) { } }
        override fun flush()=Unit
        override fun close()=Unit
    }
    private fun run(backend: Backend=Backend(),body: (BufferedLogPipeline)->Unit) {
        val pipeline=BufferedLogPipeline(backend,1,1,10);pipeline.start()
        try { body(pipeline) } finally { backend.gate.countDown();assertTrue(pipeline.stopGracefully(Duration.ofSeconds(3))) }
    }
    @Test fun watchesOnlyTouchedOwnersAndReturnsDetachedSets() = run { pipeline ->
        val watch=pipeline.observeOwners(listOf(block,player));assertTrue(watch.changedOwners().isEmpty())
        pipeline.submit(entry(change(block)));assertEquals(setOf(block),watch.changedOwners())
        val result=watch.changedOwners();runCatching { (result as? MutableSet)?.clear() };assertEquals(setOf(block),watch.changedOwners());watch.close()
    }
    @Test fun unrelatedPositionsDimensionsAndPlayersDoNotInvalidate() = run { pipeline ->
        val watch=pipeline.observeOwners(listOf(block,player))
        pipeline.submit(blockEntry(block.copy(position=BlockPosition(2,64,1))))
        pipeline.submit(blockEntry(block.copy(dimension=ResourceId.parse("minecraft:the_nether"))))
        assertTrue(watch.changedOwners().isEmpty());watch.close()
    }
    @Test fun anySlotAndTemporaryPlayerOwnersInvalidateWholePlayerInventory() = run { pipeline ->
        for(owner in listOf(player,ItemSlotOwner.Cursor(player.playerId),ItemSlotOwner.CraftingGrid(player.playerId,0))) {
            val watch=pipeline.observeOwners(listOf(player));pipeline.submit(entry(change(owner)));assertEquals(setOf(player),watch.changedOwners());watch.close()
        }
        val watch=pipeline.observeOwners(listOf(player));pipeline.submit(entry(change(player,35)));assertEquals(setOf(player),watch.changedOwners());watch.close()
    }
    @Test fun rejectedQueueAttemptsStillInvalidateAndDoNotChangeAcceptedCount() {
        val backend=Backend(true)
        run(backend) { pipeline ->
            pipeline.submit(blockEntry());assertTrue(backend.entered.await(2,TimeUnit.SECONDS));pipeline.submit(blockEntry())
            val watch=pipeline.observeOwners(listOf(player));val accepted=pipeline.metrics().accepted
            assertEquals(SubmissionResult.BACKPRESSURE,pipeline.submit(entry(change(player))));assertEquals(setOf(player),watch.changedOwners());assertEquals(accepted,pipeline.metrics().accepted);watch.close()
        }
    }
    @Test fun repeatedAndReversingAttemptsNeverResetObservation() = run { pipeline ->
        val watch=pipeline.observeOwners(listOf(block));val change=change(block)
        pipeline.submit(entry(change));pipeline.submit(entry(ItemSlotChange(change.address,change.after,change.before)))
        assertEquals(setOf(block),watch.changedOwners());watch.close()
    }
    @Test fun registrationsCopyOwnersRejectUnsupportedAndHaveBounds() = run { pipeline ->
        assertFailsWith<IllegalArgumentException> { pipeline.observeOwners(emptyList()) }
        assertFailsWith<IllegalArgumentException> { pipeline.observeOwners(listOf(ItemSlotOwner.Cursor(player.playerId))) }
        assertFailsWith<IllegalArgumentException> { pipeline.observeOwners((0..32).map { block.copy(position=BlockPosition(it,64,1)) }) }
        val owners=mutableListOf<ItemSlotOwner>(block);val watch=pipeline.observeOwners(owners);owners.clear();pipeline.submit(blockEntry());assertEquals(setOf(block),watch.changedOwners());watch.close()
        val watches=(1..32).map { pipeline.observeOwners(listOf(block)) };assertFailsWith<IllegalStateException> { pipeline.observeOwners(listOf(block)) };watches.forEach { it.close() }
        pipeline.observeOwners(listOf(block)).close()
    }
    @Test fun closingIsIdempotentAndOldHandlesCannotCertifyFreshObservations() = run { pipeline ->
        val first=pipeline.observeOwners(listOf(block));first.close();first.close();assertEquals(setOf(block),first.changedOwners())
        val second=pipeline.observeOwners(listOf(block));assertTrue(second.changedOwners().isEmpty());pipeline.submit(blockEntry());assertEquals(setOf(block),second.changedOwners());second.close()
    }
    @Test fun stoppedWriterInvalidatesOutstandingHandlesAndRejectsNewWatches() {
        val pipeline=BufferedLogPipeline(Backend(),1,1,10)
        assertFailsWith<IllegalStateException> { pipeline.observeOwners(listOf(block)) };pipeline.start()
        val watch=pipeline.observeOwners(listOf(block,player));assertTrue(pipeline.stopGracefully(Duration.ofSeconds(3)))
        assertEquals(setOf(block,player),watch.changedOwners());assertFailsWith<IllegalStateException> { pipeline.observeOwners(listOf(block)) };watch.close()
    }
    @Test fun concurrentSubmissionsCannotLoseOwnerInvalidations() = run { pipeline ->
        val owners=(0..15).map { block.copy(position=BlockPosition(it,64,1)) };val watch=pipeline.observeOwners(owners)
        val pool=Executors.newFixedThreadPool(4)
        try { pool.invokeAll(owners.map { owner -> Callable { pipeline.submit(blockEntry(owner)) } }).forEach { it.get(2,TimeUnit.SECONDS) } } finally { pool.shutdownNow() }
        assertEquals(owners.toSet(),watch.changedOwners());watch.close()
    }
}
