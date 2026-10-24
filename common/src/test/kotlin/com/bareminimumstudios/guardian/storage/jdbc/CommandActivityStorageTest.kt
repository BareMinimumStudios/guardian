package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.*
import com.bareminimumstudios.guardian.storage.query.*
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test

class CommandActivityStorageTest {
    private val alex=ActorIdentity.Player(UUID.randomUUID(),"Alex")
    private val bob=ActorIdentity.Player(UUID.randomUUID(),"Bob")
    private fun command(time: Long,actor:ActorIdentity.Player=alex,text:String="/give @s example:slate 2")=PlayerActivity(timestampEpochMillis=time,actor=actor,kind=PlayerActivityKind.COMMAND,text=text)
    private fun stores(test:(QueryableStorageBackend)->Unit) {
        listOf<QueryableStorageBackend>(InMemoryStorageBackend(),SqliteStorageBackend(Files.createTempDirectory("command-sqlite").resolve("audit.db")),DuckDbStorageBackend(Files.createTempDirectory("command-duck").resolve("audit.db"))).forEach { it.use { db->db.open();test(db) } }
    }
    @Test fun commandsAreIndependentFilterablePagedAndIdempotent()=stores { db->
        val first=command(1);val second=command(2,bob);val chat=PlayerActivity(timestampEpochMillis=3,actor=alex,kind=PlayerActivityKind.CHAT,text="hello");val login=PlayerActivity(timestampEpochMillis=4,actor=alex,kind=PlayerActivityKind.LOGIN)
        db.append(listOf(first,second,chat,login,first))
        val query=PlayerActivityQuery(setOf(PlayerActivityKind.COMMAND),limit=1)
        assertEquals(listOf(second),db.lookupActivity(query));assertEquals(listOf(first),db.lookupActivity(query.copy(offset=1)))
        assertEquals(listOf(first),db.lookupActivity(query.copy(playerName="alex")))
        assertEquals(listOf(first),db.lookupActivity(query.copy(search=HistorySearchFilter(excludedPlayers=listOf(HistoryPlayer("Bob"))))))
        assertEquals(listOf(chat),db.lookupActivity(PlayerActivityQuery(setOf(PlayerActivityKind.CHAT))))
        assertEquals(listOf(login),db.lookupActivity(PlayerActivityQuery(setOf(PlayerActivityKind.LOGIN,PlayerActivityKind.LOGOUT))))
        assertEquals(ActionType.COMMAND,first.action)
    }
    @Test fun commandsRequireBoundedImmutableText() {
        assertFailsWith<IllegalArgumentException>{command(1,text="")};assertFailsWith<IllegalArgumentException>{command(1,text="a".repeat(4097))}
        assertFailsWith<IllegalArgumentException>{PlayerActivity(timestampEpochMillis=1,actor=alex,kind=PlayerActivityKind.COMMAND)}
        assertEquals("/help",command(1,text="/help").text)
    }
    @Test fun commandHistoryParticipatesInIsolatedActivityPurge()=stores { db->
        val old=command(1);val recent=command(200);db.append(listOf(old,recent))
        val plan=db.previewPurge(100);assertEquals(1,plan.eligible.activity);assertEquals(1,db.applyPurge(plan){true}.deleted.activity)
        assertEquals(listOf(recent),db.lookupActivity(PlayerActivityQuery(setOf(PlayerActivityKind.COMMAND))))
    }
    @Test fun schema14UpgradePreservesPriorActivityAndCommandsSurviveRestart() {
        for(duck in listOf(false,true)) {
            val file=Files.createTempDirectory("command-upgrade").resolve("audit.db");fun db():QueryableStorageBackend=if(duck)DuckDbStorageBackend(file)else SqliteStorageBackend(file)
            val chat=PlayerActivity(timestampEpochMillis=1,actor=alex,kind=PlayerActivityKind.CHAT,text="kept")
            db().use{it.open();it.append(listOf(chat))}
            DriverManager.getConnection((if(duck)"jdbc:duckdb:"else "jdbc:sqlite:")+file).use { c->c.createStatement().use { s->s.executeUpdate("DROP TABLE ex_entity");s.executeUpdate("DROP TABLE IF EXISTS ex_fluid");s.executeUpdate("DELETE FROM ex_schema_migrations WHERE version>=15");s.executeUpdate("UPDATE ex_meta SET meta_value='14' WHERE meta_key='schema_version'") } }
            val command=command(2,text="/say quote ' SQL -- ☃")
            db().use{it.open();assertEquals(GuardianSchema.CURRENT_VERSION,it.health().schemaVersion);assertEquals(listOf(chat),it.lookupActivity(PlayerActivityQuery(setOf(PlayerActivityKind.CHAT))));it.append(listOf(command))}
            db().use{it.open();assertEquals(listOf(command),it.lookupActivity(PlayerActivityQuery(setOf(PlayerActivityKind.COMMAND))))}
        }
    }
}
