package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.*
import com.bareminimumstudios.guardian.storage.codec.ContainerChangesCodec
import com.bareminimumstudios.guardian.storage.query.*
import com.bareminimumstudios.guardian.logging.container.HopperTransferCorrelation
import com.bareminimumstudios.guardian.rollback.*
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

class EntityInventoryHistoryTest {
    private val dim=ResourceId.parse("minecraft:overworld")
    private val cart=ItemSlotOwner.EntityContainer(dim,UUID.randomUUID(),ResourceId.parse("minecraft:chest_minecart"),BlockPosition(1,65,1))
    private val block=ItemSlotOwner.BlockContainer(dim,BlockPosition(1,64,1))
    private val player=ActorIdentity.Player(UUID.randomUUID(),"Alice")
    private fun item(n:Int)=if(n==0)ItemStackSnapshot.EMPTY else ItemStackSnapshot(ResourceId.parse("mod:coal"),n,BinaryPayload.of(byteArrayOf(3,7)))
    private fun row(time:Long,owner:ItemSlotOwner.EntityContainer=cart)=ContainerTransactionSnapshot(UUID.randomUUID(),time,player,1,ContainerAction.QUICK_MOVE,listOf(
        ItemSlotChange(ItemSlotAddress(ItemSlotOwner.PlayerInventory(player.uuid),0),item(3),item(2)),
        ItemSlotChange(ItemSlotAddress(owner,0),item(0),item(1))))
    private fun stores(test:(QueryableStorageBackend)->Unit) {
        listOf<QueryableStorageBackend>(InMemoryStorageBackend(),SqliteStorageBackend(Files.createTempDirectory("cart-sqlite").resolve("audit.db")),DuckDbStorageBackend(Files.createTempDirectory("cart-duck").resolve("audit.db"))).forEach { it.use { db->db.open();test(db) } }
    }
    @Test fun versionedPayloadRetainsComponentsAndStableEntityIdentity() {
        val changes=row(1).changes;val bytes=ContainerChangesCodec.encode(changes)
        assertEquals(0x33,bytes[3].toInt());assertEquals(changes,ContainerChangesCodec.decode(bytes))
        for(old in listOf(0x31,0x32))assertFailsWith<IllegalArgumentException> { ContainerChangesCodec.decode(bytes.copyOf().also { it[3]=old.toByte() }) }
        assertFails { ContainerChangesCodec.decode(bytes.copyOf(bytes.size-1)) }
        assertFailsWith<IllegalArgumentException> { ContainerChangesCodec.decode(bytes+byteArrayOf(0)) }
    }
    @Test fun stableOwnerIndexFollowsMovementWithoutAliasingABlock() {
        assertEquals(ContainerOwnerIndex.key(cart),ContainerOwnerIndex.key(cart.copy(position=BlockPosition(90,70,90))))
        assertNotEquals(ContainerOwnerIndex.key(cart),ContainerOwnerIndex.key(block))
        assertNotEquals(ContainerOwnerIndex.key(cart),ContainerOwnerIndex.key(cart.copy(dimension=ResourceId.parse("minecraft:the_nether"))))
    }
    @Test fun uuidActorTimeAndRecordedSpatialFiltersPrecedePaging()=stores { db->
        val a=row(1);val moved=cart.copy(position=BlockPosition(100,65,100));val b=row(2,moved);val other=row(3,cart.copy(entityUuid=UUID.randomUUID()))
        db.append(listOf(a,b,other).map(::ContainerAuditEntry))
        val q=ContainerLookupQuery(dimension=dim,entityUuid=cart.entityUuid,actorName="alice",limit=1)
        assertEquals(listOf(b.transactionId),db.lookupContainers(q).map { it.transactionId })
        assertEquals(listOf(a.transactionId),db.lookupContainers(q.copy(offset=1)).map { it.transactionId })
        assertEquals(listOf(b.transactionId),db.lookupContainers(q.copy(afterEpochMillis=2)).map { it.transactionId })
        assertEquals(listOf(a.transactionId),db.lookupContainers(q.copy(position=cart.position)).map { it.transactionId })
        assertTrue(db.lookupContainers(q.copy(dimension=ResourceId.parse("minecraft:the_nether"))).isEmpty())
    }
    @Test fun entityPayloadsAndIndexSurviveRestartAndPurge() {
        for(duck in listOf(false,true)) {
            val path=Files.createTempDirectory("cart-restart").resolve("audit.db")
            fun db():QueryableStorageBackend=if(duck)DuckDbStorageBackend(path)else SqliteStorageBackend(path)
            val a=row(1);val b=row(200)
            db().use { it.open();it.append(listOf(ContainerAuditEntry(a),ContainerAuditEntry(b))) }
            db().use { it.open();assertEquals(listOf(b.transactionId,a.transactionId),it.lookupContainers(ContainerLookupQuery(entityUuid=cart.entityUuid)).map { t->t.transactionId });val p=it.previewPurge(100);assertEquals(1L,it.applyPurge(p){true}.deleted.containers) }
            DriverManager.getConnection((if(duck)"jdbc:duckdb:" else "jdbc:sqlite:")+path).use { c->c.createStatement().use { s->s.executeQuery("SELECT COUNT(*) FROM ex_container_entity").use { rs->assertTrue(rs.next());assertEquals(1,rs.getInt(1)) } } }
        }
    }
    @Test fun schema24UpgradePreservesExistingItemRows() {
        for(duck in listOf(false,true)) {
            val path=Files.createTempDirectory("cart-upgrade").resolve("audit.db")
            fun db():QueryableStorageBackend=if(duck)DuckDbStorageBackend(path)else SqliteStorageBackend(path)
            val old=ContainerTransactionSnapshot(UUID.randomUUID(),1,player,1,ContainerAction.BLOCK_USE,listOf(ItemSlotChange(ItemSlotAddress(block,0),item(0),item(1))))
            db().use { it.open();it.append(listOf(ContainerAuditEntry(old))) }
            DriverManager.getConnection((if(duck)"jdbc:duckdb:" else "jdbc:sqlite:")+path).use { c->c.createStatement().use { s->LegacyMovementSchema.stripPrivateActivity(s);s.executeUpdate("DROP TABLE ex_container_entity");s.executeUpdate("DELETE FROM ex_schema_migrations WHERE version>=25");s.executeUpdate("UPDATE ex_meta SET meta_value='24' WHERE meta_key='schema_version'") } }
            val fresh=row(2)
            db().use { it.open();assertEquals(GuardianSchema.CURRENT_VERSION,it.health().schemaVersion);assertEquals(old.transactionId,it.lookupContainers(ContainerLookupQuery()).single().transactionId);it.append(listOf(ContainerAuditEntry(fresh))) }
            db().use { it.open();assertEquals(fresh.changes,it.lookupContainers(ContainerLookupQuery(entityUuid=cart.entityUuid)).single().changes) }
        }
    }
    @Test fun conservationOnlyIndexesTheCartWhichActuallyChanged() {
        val other=cart.copy(entityUuid=UUID.randomUUID(),position=BlockPosition(2,65,1))
        val a=ItemSlotAddress(block,0);val b=ItemSlotAddress(cart,0);val c=ItemSlotAddress(other,0)
        val before=InventorySnapshot(mapOf(a to item(2),b to item(0),c to item(0)))
        val value=assertNotNull(HopperTransferCorrelation(before,setOf(block),setOf(cart,other)).finish(InventorySnapshot(mapOf(a to item(1),b to item(1),c to item(0)))))
        assertEquals(setOf(block,cart.auditLocation()),value.containers.toSet())
        assertFalse(ContainerLookupQuery(entityUuid=other.entityUuid).matches(value))
        assertFalse(ContainerRollbackPlanner.supported(value))
        assertEquals(ContainerPreviewReason.UNSUPPORTED_OWNER,ContainerRollbackPlanner.plan(listOf(value),InventorySnapshot(value.changes.associate { it.address to it.after })).entries.single().reason)
    }
    @Test fun collectionActionsCannotBecomeRollbackCandidates() {
        val value=ContainerTransactionSnapshot(UUID.randomUUID(),1,ActorIdentity.System("minecraft:hopper_minecart"),0,ContainerAction.HOPPER_COLLECT,listOf(ItemSlotChange(ItemSlotAddress(cart,0),item(0),item(1))))
        assertEquals(ContainerPreviewReason.UNSUPPORTED_ACTION,ContainerRollbackPlanner.plan(listOf(value),InventorySnapshot(value.changes.associate { it.address to it.after })).entries.single().reason)
        assertEquals(18,ChangeCause.LECTERN.storageCode)
    }
}
