package com.bareminimumstudios.guardian.export
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.*
import com.bareminimumstudios.guardian.storage.jdbc.*
import com.bareminimumstudios.guardian.storage.query.*
import com.bareminimumstudios.guardian.command.ExportFilters
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
class HistoryExportTest {
    private val actor=ActorIdentity.Player(UUID.randomUUID(),"Alice")
    private fun event(text:String="quote \"\n\t\\ snow \u2603",kind:PlayerActivityKind=PlayerActivityKind.CHAT)=PlayerActivity(timestampEpochMillis=1,actor=actor,kind=kind,text=if(kind in setOf(PlayerActivityKind.CHAT,PlayerActivityKind.COMMAND))text else null)
    private fun block(bytes:Int=3)=StoredBlockChange(1,BlockChangeSnapshot(1,actor,ResourceId.parse("overworld"),BlockPosition(1,64,2),BlockStateSnapshot(ResourceId.parse("air")),BlockStateSnapshot(ResourceId.parse("oak_sign"),emptyMap(),BinaryPayload.of(ByteArray(bytes){1})),ChangeCause.SIGN_TEXT,ActionType.BLOCK_CHANGE),BlockRollbackState.ACTIVE)
    private fun write(root:java.nio.file.Path,rows:List<Any>,permit:()->Boolean={true})=ExportFiles.write(root,ExportKind.CHAT,500,"t:1h","console",100,emptyMap(),rows,permit)
    private fun files(root:java.nio.file.Path)=if(Files.exists(root.resolve("exports")))Files.list(root.resolve("exports")).use{it.toList()}else emptyList()
    @Test fun utf8JsonLinesEscapeControlsAndContainMetadataWithoutCsvFormulas() {
        val root=Files.createTempDirectory("export-json");val result=write(root,listOf(event(),event("=1+1")))
        val lines=Files.readAllLines(root.resolve("exports").resolve(result.filename));assertEquals(3,lines.size)
        assertTrue("\"formatVersion\":1" in lines[0]);assertTrue("\\u000a" in lines[1] && "\\u0009" in lines[1] && "\\\\" in lines[1]);assertTrue("=1+1" in lines[2])
        assertEquals(2,result.count);assertFalse(result.mayHaveMore);assertTrue(files(root).all{it.toString().endsWith(".jsonl")})
    }
    @Test fun blockSnapshotsAndItemComponentsRemainVersionedOpaqueBytes() {
        val row=block();val projected=ExportRows.value(row);val state=projected["after"] as Map<*,*>
        assertContentEquals(row.snapshot.after.blockEntityData!!.copyBytes(),java.util.Base64.getDecoder().decode(state["blockEntityBase64"] as String))
        val item=ItemStackSnapshot(ResourceId.parse("coal"),2,BinaryPayload.of(byteArrayOf(7,8)))
        val txn=ContainerTransactionSnapshot(UUID.randomUUID(),1,actor,1,ContainerAction.PICKUP,listOf(ItemSlotChange(ItemSlotAddress(ItemSlotOwner.PlayerInventory(actor.uuid),0),item,ItemStackSnapshot.EMPTY)))
        assertEquals(2,ExportRows.payloadBytes(txn));assertEquals("item_transaction",ExportRows.value(txn)["recordType"])
    }
    @Test fun cancellationBeforeCreationAndDuringFlushLeavesNoFile() {
        val root=Files.createTempDirectory("export-cancel")
        assertFails{write(root,listOf(event())){false}};assertTrue(files(root).isEmpty())
        var calls=0;assertFails{write(root,listOf(event())){++calls<3}};assertTrue(files(root).isEmpty())
    }
    @Test fun oversizedSnapshotsAreRefusedBeforeWriting() {
        val root=Files.createTempDirectory("export-size")
        assertFails{write(root,listOf(block(4*1024*1024+1)))};assertTrue(files(root).isEmpty())
        assertFails{write(root,listOf(block(4*1024*1024),block(4*1024*1024),block(1)))};assertTrue(files(root).isEmpty())
    }
    @Test fun byteLimitFailureRemovesPartialRatherThanPublishingTruncatedJson() {
        val root=Files.createTempDirectory("export-byte-limit");val b=block().let{it.copy(snapshot=it.snapshot.copy(after=BlockStateSnapshot(ResourceId.parse("stone"),mapOf("large" to "x".repeat(17*1024*1024)))))}
        assertFails{write(root,listOf(b))};assertTrue(files(root).isEmpty())
    }
    @Test fun fullDirectoryAndNonDirectoryPathsPreserveExistingFiles() {
        val root=Files.createTempDirectory("export-quota");Files.createDirectory(root.resolve("exports"));repeat(64){Files.writeString(root.resolve("exports/keep$it.jsonl"),"keep")}
        assertFails{write(root,listOf(event()))};assertEquals(64,files(root).size);assertEquals("keep",Files.readString(root.resolve("exports/keep0.jsonl")))
        val other=Files.createTempDirectory("export-path");Files.writeString(other.resolve("exports"),"preserve");assertFails{write(other,listOf(event()))};assertEquals("preserve",Files.readString(other.resolve("exports")))
    }
    @Test fun limitIsHonestAndDoesNotClaimCompleteHistory() {
        val root=Files.createTempDirectory("export-limit")
        val result=ExportFiles.write(root,ExportKind.CHAT,1,"t:1h l:1","console",100,emptyMap(),listOf(event())){true}
        assertTrue(result.mayHaveMore);assertTrue("\"mayHaveMore\":true" in Files.readAllLines(root.resolve("exports").resolve(result.filename))[0])
        assertFails{ExportFiles.write(root,ExportKind.CHAT,1,"t:1h","console",100,emptyMap(),listOf(event(),event())){true}}
    }
    @Test fun categoryQueriesRespectFiltersOrderAndDoNotMutateHistory() {
        for(db in listOf<QueryableStorageBackend>(InMemoryStorageBackend(),SqliteStorageBackend(Files.createTempDirectory("export-sqlite").resolve("audit.db")),DuckDbStorageBackend(Files.createTempDirectory("export-duck").resolve("audit.db"))))db.use {
            it.open();it.append(listOf(event("older"),event("newer").copy(timestampEpochMillis=2),event("login",PlayerActivityKind.LOGIN)))
            val q=ExportQuery.Activity(ExportKind.CHAT,PlayerActivityQuery(setOf(PlayerActivityKind.CHAT),limit=1,search=HistorySearchFilter(players=listOf(HistoryPlayer("Alice")))))
            val rows=q.read(it);assertEquals("newer",rows.single().text)
            write(Files.createTempDirectory("export-read-only"),rows)
            assertEquals(3,it.lookupActivity(PlayerActivityQuery(PlayerActivityKind.entries.toSet())).size)
        }
    }
    @Test fun parsersRequireTimeRejectIgnoredFiltersAndKeepCategoryActions() {
        for((kind,raw) in listOf(ExportKind.BLOCKS to "u:Alice",ExportKind.CHAT to "t:1h r:5",ExportKind.CHAT to "t:1h i:stone",ExportKind.ITEMS to "t:1h a:place",ExportKind.BLOCKS to "t:1h p:2",ExportKind.ENTITIES to "t:1h c:fire"))assertTrue(ExportFilters.parse(kind,raw).isFailure)
        assertEquals(setOf(EntityAuditKind.DEATH),ExportFilters.parse(ExportKind.ENTITIES,"t:1h a:death").getOrThrow().entityKinds)
        assertEquals(setOf(FluidAuditKind.FLOW),ExportFilters.parse(ExportKind.FLUIDS,"t:1h a:flow").getOrThrow().fluidKinds)
        assertEquals(500,ExportFilters.parse(ExportKind.BLOCKS,"t:1h u:Alice,Bob i:stone l:500").getOrThrow().common.limit)
    }
}
