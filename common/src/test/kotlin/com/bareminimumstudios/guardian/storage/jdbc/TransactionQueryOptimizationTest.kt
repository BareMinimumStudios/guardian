package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.*
import com.bareminimumstudios.guardian.storage.query.*
import java.sql.DriverManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class TransactionQueryOptimizationTest {
    private val actor=ActorIdentity.Player(UUID.randomUUID(),"Fixture")
    private val world=ResourceId.parse("minecraft:overworld")
    private val local=ItemSlotOwner.BlockContainer(world,BlockPosition(10,64,10))
    private val remote=ItemSlotOwner.BlockContainer(ResourceId.parse("minecraft:the_nether"),BlockPosition(-500,64,-500))
    private val item=ItemStackSnapshot(ResourceId.parse("minecraft:coal"),1,BinaryPayload.of(byteArrayOf(1)))
    private fun row(i:Int,blocks:List<ItemSlotOwner.BlockContainer> = listOf(local,remote))=ContainerTransactionSnapshot(UUID(0,i.toLong()+1),i.toLong(),actor,0,ContainerAction.QUICK_MOVE,
        listOf(ItemSlotChange(ItemSlotAddress(ItemSlotOwner.PlayerInventory(actor.uuid),0),ItemStackSnapshot.EMPTY,item)),blocks)
    private fun stores(test:(QueryableStorageBackend,Path,String)->Unit) {
        for(duck in listOf(false,true)) {
            val path=Files.createTempDirectory("transaction-opt").resolve("audit.db")
            val db=if(duck)DuckDbStorageBackend(path) else SqliteStorageBackend(path)
            db.use { it.open();test(it,path,if(duck)"jdbc:duckdb:" else "jdbc:sqlite:") }
        }
    }
    /** Lookup regression, not an insert-throughput benchmark. Seed valid payloads through the API,
     * then bulk-clone their immutable contents and indexes inside this isolated test database. */
    private fun seed(db:QueryableStorageBackend,path:Path,jdbc:String,rows:List<ContainerTransactionSnapshot>) {
        val first=rows.first();db.append(listOf(ContainerAuditEntry(first)))
        if(rows.size==1)return
        val values=rows.drop(1).joinToString(",") { "('"+it.transactionId+"',"+it.timestampEpochMillis+")" }
        val prefix="WITH fixture(id,time) AS (VALUES $values) "
        DriverManager.getConnection(jdbc+path.toAbsolutePath()).use { conn->
            conn.autoCommit=false
            conn.createStatement().use { sql->
                sql.executeUpdate(prefix+"INSERT INTO ex_container(transaction_uuid,time,actor,menu_id,interaction,changes) SELECT f.id,f.time,c.actor,c.menu_id,c.interaction,c.changes FROM fixture f CROSS JOIN ex_container c WHERE c.transaction_uuid='"+first.transactionId+"'")
                sql.executeUpdate(prefix+"INSERT INTO ex_container_location(transaction_uuid,wid,x,y,z) SELECT f.id,c.wid,c.x,c.y,c.z FROM fixture f CROSS JOIN ex_container_location c WHERE c.transaction_uuid='"+first.transactionId+"'")
                sql.executeUpdate(prefix+"INSERT INTO ex_container_owner(transaction_uuid,owner_key,time) SELECT f.id,c.owner_key,f.time FROM fixture f CROSS JOIN ex_container_owner c WHERE c.transaction_uuid='"+first.transactionId+"'")
                sql.executeUpdate(prefix+"INSERT INTO ex_container_item(transaction_uuid,resource) SELECT f.id,c.resource FROM fixture f CROSS JOIN ex_container_item c WHERE c.transaction_uuid='"+first.transactionId+"'")
            }
            conn.commit()
        }
    }
    @Test fun fullPagesRetainAllEventTimeContextsAndChanges()=stores { db,path,jdbc->
        val rows=(0 until 501).map { row(it) };seed(db,path,jdbc,rows)
        val actual=db.lookupContainers(ContainerLookupQuery(world,local.position,radius=5,limit=500))
        assertEquals(rows.drop(1).reversed().map { it.transactionId },actual.map { it.transactionId })
        actual.forEach { assertEquals(setOf(local,remote),it.containers.toSet());assertEquals(rows.first().changes,it.changes) }
        val last=db.lookupContainers(ContainerLookupQuery(world,local.position,radius=5,limit=500,offset=500)).single()
        assertEquals(rows.first().transactionId,last.transactionId)
    }
    @Test fun denseFallbackMatchesOracleWithBothOrdersPagesAndBounds()=stores { db,path,jdbc->
        val rows=(0 until 2050).map { row(it) };seed(db,path,jdbc,rows)
        val scopes=listOf(ContainerLookupQuery(world,local.position,radius=10),ContainerLookupQuery(world,bounds=BlockBounds(BlockPosition(0,54,0),BlockPosition(20,74,20))))
        for(base in scopes)for(oldest in listOf(false,true)) {
            val q=base.copy(limit=7,offset=3,oldestFirst=oldest,afterEpochMillis=100)
            val ordered=rows.filter(q::matches).sortedBy { it.timestampEpochMillis }
            assertEquals((if(oldest)ordered else ordered.reversed()).drop(3).take(7).map { it.transactionId },db.lookupContainers(q).map { it.transactionId })
        }
    }
    @Test fun emptyAndUnlocatedTransactionsRemainDistinctFromSpatialHistory()=stores { db,path,jdbc->
        val unlocated=row(1,emptyList());db.append(listOf(ContainerAuditEntry(unlocated)))
        assertTrue(db.lookupContainers(ContainerLookupQuery(world,local.position,radius=10)).isEmpty())
        val value=db.lookupContainers(ContainerLookupQuery()).single()
        assertEquals(unlocated.transactionId,value.transactionId);assertTrue(value.containers.isEmpty())
    }
}
