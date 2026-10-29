package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.*
import com.bareminimumstudios.guardian.storage.query.*
import com.bareminimumstudios.guardian.export.*
import com.bareminimumstudios.guardian.command.ExportFilters
import com.bareminimumstudios.guardian.logging.CommandAuditPolicy
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

class WhisperHistoryTest {
    private val alice=ActorIdentity.Player(UUID.randomUUID(),"Alice")
    private val bob=ActorIdentity.Player(UUID.randomUUID(),"Bob")
    private val carol=ActorIdentity.Player(UUID.randomUUID(),"Carol")
    private fun row(time:Long,from:ActorIdentity.Player=alice,to:ActorIdentity.Player=bob)=PlayerActivity(timestampEpochMillis=time,actor=from,kind=PlayerActivityKind.WHISPER,text="private text",recipientUuid=to.uuid,recipientName=to.lastKnownName)
    private fun stores(test:(QueryableStorageBackend)->Unit) {
        listOf<QueryableStorageBackend>(InMemoryStorageBackend(),SqliteStorageBackend(Files.createTempDirectory("whisper-sqlite").resolve("audit.db")),DuckDbStorageBackend(Files.createTempDirectory("whisper-duck").resolve("audit.db"))).forEach { it.use { db->db.open();test(db) } }
    }
    @Test fun senderAndRecipientFiltersRunBeforePagingAndExclusionsWin()=stores { db->
        val sent=row(1);val received=row(2,bob,alice);val excluded=row(3,carol,alice);val unrelated=row(4,bob,carol)
        db.append(listOf(sent,received,excluded,unrelated))
        val q=PlayerActivityQuery(setOf(PlayerActivityKind.WHISPER),limit=1,search=HistorySearchFilter(players=listOf(HistoryPlayer("Alice")),excludedPlayers=listOf(HistoryPlayer("Carol"))))
        assertEquals(listOf(received),db.lookupActivity(q))
        assertEquals(listOf(sent),db.lookupActivity(q.copy(offset=1)))
        assertTrue(db.lookupActivity(q.copy(afterEpochMillis=3)).isEmpty())
        assertEquals(listOf(excluded,received,sent),db.lookupActivity(q.copy(limit=10,search=HistorySearchFilter(),playerUuid=alice.uuid)))
        assertEquals(listOf(excluded,received,sent),db.lookupActivity(q.copy(limit=10,search=HistorySearchFilter(),playerName="ALICE")))
    }
    @Test fun ordinaryChatAndCommandQueriesNeverRevealWhispers()=stores { db->
        db.append(listOf(row(1),PlayerActivity(timestampEpochMillis=2,actor=alice,kind=PlayerActivityKind.CHAT,text="public"),PlayerActivity(timestampEpochMillis=3,actor=alice,kind=PlayerActivityKind.COMMAND,text="/help")))
        assertEquals(listOf("public"),db.lookupActivity(PlayerActivityQuery(setOf(PlayerActivityKind.CHAT))).map { it.text })
        assertEquals(listOf("/help"),db.lookupActivity(PlayerActivityQuery(setOf(PlayerActivityKind.COMMAND))).map { it.text })
    }
    @Test fun mixedKindsKeepRecipientMatchingScopedToPrivateRows()=stores { db->
        val private=row(1);val public=PlayerActivity(timestampEpochMillis=2,actor=bob,kind=PlayerActivityKind.CHAT,text="public")
        db.append(listOf(private,public))
        assertEquals(listOf(private),db.lookupActivity(PlayerActivityQuery(setOf(PlayerActivityKind.CHAT,PlayerActivityKind.WHISPER),search=HistorySearchFilter(players=listOf(HistoryPlayer("Alice"))))))
    }
    @Test fun restartAndSchema25UpgradePreservePriorActivity() {
        for(duck in listOf(false,true)) {
            val path=Files.createTempDirectory("whisper-upgrade").resolve("audit.db")
            fun db():QueryableStorageBackend=if(duck)DuckDbStorageBackend(path)else SqliteStorageBackend(path)
            val old=PlayerActivity(timestampEpochMillis=1,actor=alice,kind=PlayerActivityKind.CHAT,text="kept")
            db().use { it.open();it.append(listOf(old)) }
            DriverManager.getConnection((if(duck)"jdbc:duckdb:" else "jdbc:sqlite:")+path).use { c->c.createStatement().use { s->LegacyMovementSchema.stripPrivateActivity(s);s.executeUpdate("DELETE FROM ex_schema_migrations WHERE version>=26");s.executeUpdate("UPDATE ex_meta SET meta_value='25' WHERE meta_key='schema_version'") } }
            val new=row(2)
            db().use { it.open();assertEquals(GuardianSchema.CURRENT_VERSION,it.health().schemaVersion);assertEquals(listOf(old),it.lookupActivity(PlayerActivityQuery(setOf(PlayerActivityKind.CHAT))));it.append(listOf(new)) }
            db().use { it.open();assertEquals(listOf(new),it.lookupActivity(PlayerActivityQuery(setOf(PlayerActivityKind.WHISPER)))) }
        }
    }
    @Test fun whisperHistoryParticipatesInExistingBoundedActivityPurge()=stores { db->
        val old=row(1);val recent=row(200);db.append(listOf(old,recent))
        val preview=db.previewPurge(100);assertEquals(1L,preview.eligible.activity)
        assertEquals(1L,db.applyPurge(preview){true}.deleted.activity)
        assertEquals(listOf(recent),db.lookupActivity(PlayerActivityQuery(setOf(PlayerActivityKind.WHISPER))))
    }
    @Test fun privateExportsRequireTheirOwnCategoryAndRejectLocationFilters() {
        assertEquals("guardian.whispers",ExportKind.WHISPERS.permission)
        assertTrue(ExportFilters.parse(ExportKind.WHISPERS,"t:1h u:Alice").isSuccess)
        assertTrue(ExportFilters.parse(ExportKind.WHISPERS,"t:1h r:5").isFailure)
        assertFailsWith<IllegalArgumentException> { ExportQuery.Activity(ExportKind.CHAT,PlayerActivityQuery(setOf(PlayerActivityKind.WHISPER))) }
        val json=ExportRows.value(row(1)).toString()
        assertTrue("recipientUuid" in json && "Bob" in json)
    }
    @Test fun recipientsAreRequiredOnlyForWhispersAndAuthenticationStaysExcluded() {
        assertFailsWith<IllegalArgumentException> { PlayerActivity(timestampEpochMillis=1,actor=alice,kind=PlayerActivityKind.WHISPER,text="private") }
        assertFailsWith<IllegalArgumentException> { PlayerActivity(timestampEpochMillis=1,actor=alice,kind=PlayerActivityKind.CHAT,text="public",recipientUuid=bob.uuid,recipientName="Bob") }
        for(command in listOf("/login password","/register password","/minecraft:msg Bob secret","/tell Bob secret","/w Bob secret"))assertNull(CommandAuditPolicy.snapshot(command,true))
    }
}
