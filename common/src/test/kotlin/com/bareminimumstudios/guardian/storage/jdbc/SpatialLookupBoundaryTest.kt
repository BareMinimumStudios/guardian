package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.*
import com.bareminimumstudios.guardian.storage.query.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class SpatialLookupBoundaryTest {
    private val world=ResourceId.parse("minecraft:overworld")
    private val actor=ActorIdentity.Player(UUID.randomUUID(),"BoundaryTest")
    private val item=ItemStackSnapshot(ResourceId.parse("minecraft:coal"),1,BinaryPayload.of(byteArrayOf(1)))
    private fun position(axis:Int,value:Int)=when(axis) { 0->BlockPosition(value,0,0);1->BlockPosition(0,value,0);else->BlockPosition(0,0,value) }
    private fun stores(test:(QueryableStorageBackend)->Unit) {
        listOf<QueryableStorageBackend>(InMemoryStorageBackend(),SqliteStorageBackend(Files.createTempDirectory("bounds-sqlite").resolve("audit.db")),DuckDbStorageBackend(Files.createTempDirectory("bounds-duck").resolve("audit.db"))).forEach { it.use { db->db.open();test(db) } }
    }
    @Test fun blockRadiusDoesNotWrapAtEitherCoordinateBoundary()=stores { db->
        for(axis in 0..2) {
            val points=listOf(Int.MIN_VALUE,Int.MIN_VALUE+1,Int.MAX_VALUE-1,Int.MAX_VALUE)
            val rows=points.mapIndexed { i,x->BlockChangeSnapshot((axis*10+i+1).toLong(),actor,world,position(axis,x),
                BlockStateSnapshot(ResourceId.parse("minecraft:air")),BlockStateSnapshot(ResourceId.parse("minecraft:stone")),ChangeCause.PLAYER,ActionType.BLOCK_PLACE) }
            db.append(rows)
            assertEquals(rows.take(2).map { it.timestampEpochMillis }.toSet(),db.lookupBlocks(BlockLookupQuery(dimension=world,position=position(axis,Int.MIN_VALUE),radius=2)).map { it.snapshot.timestampEpochMillis }.toSet())
            assertEquals(rows.takeLast(2).map { it.timestampEpochMillis }.toSet(),db.lookupBlocks(BlockLookupQuery(dimension=world,position=position(axis,Int.MAX_VALUE),radius=2)).map { it.snapshot.timestampEpochMillis }.toSet())
        }
    }
    @Test fun itemRadiusUsesTheSameNonWrappingBoundsOnEveryBackend()=stores { db->
        for(axis in 0..2) {
            val points=listOf(Int.MIN_VALUE,Int.MIN_VALUE+1,Int.MAX_VALUE-1,Int.MAX_VALUE)
            val rows=points.mapIndexed { i,x->ContainerTransactionSnapshot(UUID.randomUUID(),(axis*10+i+1).toLong(),actor,1,ContainerAction.BLOCK_USE,
                listOf(ItemSlotChange(ItemSlotAddress(ItemSlotOwner.BlockContainer(world,position(axis,x)),0),ItemStackSnapshot.EMPTY,item))) }
            db.append(rows.map(::ContainerAuditEntry))
            assertEquals(rows.take(2).map { it.transactionId }.toSet(),db.lookupContainers(ContainerLookupQuery(dimension=world,position=position(axis,Int.MIN_VALUE),radius=2)).map { it.transactionId }.toSet())
            assertEquals(rows.takeLast(2).map { it.transactionId }.toSet(),db.lookupContainers(ContainerLookupQuery(dimension=world,position=position(axis,Int.MAX_VALUE),radius=2)).map { it.transactionId }.toSet())
        }
    }
}
