package com.bareminimumstudios.guardian.storage.jdbc

import kotlin.test.*
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.*
import com.bareminimumstudios.guardian.storage.query.*
import java.util.UUID
import java.nio.file.Files
import java.sql.DriverManager

class HistorySearchStorageTest {
    private val alex=ActorIdentity.Player(UUID.randomUUID(),"Alex")
    private val bob=ActorIdentity.Player(UUID.randomUUID(),"Bob")
    private val world=ResourceId.parse("minecraft:overworld")
    private val owner=ItemSlotOwner.BlockContainer(world,BlockPosition(1,64,1))
    private fun item(id: String)=ItemStackSnapshot(ResourceId.parse(id),1,BinaryPayload.of(byteArrayOf(1)))
    private fun transaction(time:Long,actor:ActorIdentity, vararg ids:String)=ContainerTransactionSnapshot(UUID.randomUUID(),time,actor,1,
        if(actor is ActorIdentity.System) ContainerAction.HOPPER_TRANSFER else ContainerAction.PICKUP,
        ids.mapIndexed { i,id -> ItemSlotChange(ItemSlotAddress(owner,i),ItemStackSnapshot.EMPTY,item(id)) },listOf(owner))
    private fun block(time:Long,actor:ActorIdentity,before:String,after:String)=BlockChangeSnapshot(time,actor,world,owner.position,BlockStateSnapshot(ResourceId.parse(before)),BlockStateSnapshot(ResourceId.parse(after)),ChangeCause.PLAYER,ActionType.BLOCK_CHANGE)
    private fun stores(test:(QueryableStorageBackend)->Unit) {
        listOf<QueryableStorageBackend>(InMemoryStorageBackend(),SqliteStorageBackend(Files.createTempDirectory("guardian-filter").resolve("history.sqlite")),DuckDbStorageBackend(Files.createTempDirectory("guardian-filter-duck").resolve("history.duckdb"))).forEach { it.use { db ->db.open();test(db) } }
    }
    @Test fun itemFiltersRunBeforePagingAndExcludeWholeTransaction() = stores { db ->
        val a=transaction(10,alex,"coal");val b=transaction(20,bob,"example:slate");val c=transaction(30,alex,"coal","diamond");val d=transaction(40,bob,"dirt")
        db.append(listOf(a,b,c,d).map(::ContainerAuditEntry))
        val search=HistorySearchFilter(players=listOf(HistoryPlayer("Alex"),HistoryPlayer("Bob")),included=setOf(ResourceId.parse("coal"),ResourceId.parse("example:slate")),excluded=setOf(ResourceId.parse("diamond")))
        assertEquals(listOf(b.transactionId),db.lookupContainers(ContainerLookupQuery(search=search,limit=1)).map { it.transactionId })
        assertEquals(listOf(a.transactionId),db.lookupContainers(ContainerLookupQuery(search=search,limit=1,offset=1)).map { it.transactionId })
        assertTrue(db.lookupContainers(ContainerLookupQuery(search=search,limit=1,offset=2)).isEmpty())
        assertEquals(listOf(a.transactionId),db.lookupContainers(ContainerLookupQuery(search=search.copy(excludedPlayers=listOf(HistoryPlayer("Bob"))))).map { it.transactionId })
        db.append(listOf(ContainerAuditEntry(a)))
        assertEquals(4,db.lookupContainers(ContainerLookupQuery(limit=20)).size)
    }
    @Test fun blockFiltersMatchBeforeOrAfterAndKeepUnknownActorsWhenExcluded() = stores { db ->
        val a=block(10,alex,"example:slate","air");val b=block(20,bob,"air","stone");val c=block(30,ActorIdentity.Unknown,"dirt","stone")
        db.append(listOf(a,b,c))
        val filter=HistorySearchFilter(included=setOf(ResourceId.parse("example:slate")))
        assertEquals(listOf(a),db.lookupBlocks(BlockLookupQuery(search=filter)).map { it.snapshot })
        val excluded=HistorySearchFilter(excludedPlayers=listOf(HistoryPlayer("Bob")),excluded=setOf(ResourceId.parse("air")))
        assertEquals(listOf(c),db.lookupBlocks(BlockLookupQuery(search=excluded)).map { it.snapshot })
        val resolved = HistorySearchFilter(players=listOf(HistoryPlayer("OldName",bob.uuid)))
        assertEquals(listOf(b),db.lookupBlocks(BlockLookupQuery(search=resolved)).map { it.snapshot })
    }
    @Test fun activityMultiUserExclusionAppliesBeforePaging() = stores { db ->
        val a=PlayerActivity(timestampEpochMillis=10,actor=alex,kind=PlayerActivityKind.CHAT,text="a")
        val b=PlayerActivity(timestampEpochMillis=20,actor=bob,kind=PlayerActivityKind.CHAT,text="b")
        db.append(listOf(a,b))
        val filter=HistorySearchFilter(players=listOf(HistoryPlayer("Alex"),HistoryPlayer("Bob")),excludedPlayers=listOf(HistoryPlayer("Bob")))
        assertEquals(listOf(a),db.lookupActivity(PlayerActivityQuery(setOf(PlayerActivityKind.CHAT),search=filter,limit=1)))
    }
    @Test fun migrationBackfillsOldItemPayloadsAndKeepsBytes() {
        for(duck in listOf(false,true)) {
            val file=Files.createTempDirectory("guardian-filter-upgrade").resolve("history.db")
            fun db():QueryableStorageBackend=if(duck) DuckDbStorageBackend(file) else SqliteStorageBackend(file)
            val old=transaction(10,alex,"example:slate");db().use { it.open();it.append(listOf(ContainerAuditEntry(old))) }
            val url=(if(duck)"jdbc:duckdb:" else "jdbc:sqlite:")+file
            var bytes:ByteArray?=null
            DriverManager.getConnection(url).use { c ->c.createStatement().use { s ->
                s.executeQuery("SELECT changes FROM ex_container").use { r ->assertTrue(r.next());bytes=r.getBytes(1) }
                s.executeUpdate("DROP TABLE ex_container_item");s.executeUpdate("DROP TABLE ex_entity");s.executeUpdate("DROP TABLE IF EXISTS ex_fluid");s.executeUpdate("DELETE FROM ex_schema_migrations WHERE version >= 12")
                s.executeUpdate("UPDATE ex_meta SET meta_value = '11' WHERE meta_key = 'schema_version'")
            } }
            db().use { it.open();assertEquals(18,it.health().schemaVersion);assertEquals(listOf(old.transactionId),it.lookupContainers(ContainerLookupQuery(search=HistorySearchFilter(included=setOf(ResourceId.parse("example:slate"))))).map { it.transactionId }) }
            DriverManager.getConnection(url).use { c ->c.createStatement().use { s ->s.executeQuery("SELECT changes FROM ex_container").use { r ->assertTrue(r.next());assertContentEquals(bytes,r.getBytes(1)) } } }
            db().use { it.open();assertEquals(listOf(old.transactionId),it.lookupContainers(ContainerLookupQuery(search=HistorySearchFilter(included=setOf(ResourceId.parse("example:slate"))))).map { it.transactionId }) }
        }
    }
    @Test fun migrationBackfillCrossesTheKeysetBatchBoundary() {
        val file=Files.createTempDirectory("guardian-filter-pages").resolve("history.sqlite")
        val rows=(1..129).map { transaction(it.toLong(),alex,"example:slate") }
        SqliteStorageBackend(file).use { it.open();it.append(rows.map(::ContainerAuditEntry)) }
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->c.createStatement().use { s ->
            s.executeUpdate("DROP TABLE ex_container_item")
            s.executeUpdate("DROP TABLE ex_entity");s.executeUpdate("DROP TABLE IF EXISTS ex_fluid");s.executeUpdate("DELETE FROM ex_schema_migrations WHERE version >= 12")
            s.executeUpdate("UPDATE ex_meta SET meta_value = '11' WHERE meta_key = 'schema_version'")
        } }
        SqliteStorageBackend(file).use { db ->db.open()
            val query=ContainerLookupQuery(limit=500,search=HistorySearchFilter(included=setOf(ResourceId.parse("example:slate"))))
            assertEquals(rows.asReversed().map { it.transactionId },db.lookupContainers(query).map { it.transactionId })
        }
    }
}
