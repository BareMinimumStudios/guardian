package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.*
import com.bareminimumstudios.guardian.storage.query.PlayerActivityQuery
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

class PlayerActivityStorageTest {
    private val uuid = UUID.fromString("12345678-1111-4222-8333-444444444444")
    private fun row(kind: PlayerActivityKind, time: Long, name: String = "PlayerOne", text: String? = null) =
        PlayerActivity(timestampEpochMillis = time, actor = ActorIdentity.Player(uuid, name), kind = kind, text = text)
    private fun query(vararg kinds: PlayerActivityKind) = PlayerActivityQuery(kinds.toSet())
    private fun stores(test: (QueryableStorageBackend) -> Unit) {
        listOf<QueryableStorageBackend>(InMemoryStorageBackend(), SqliteStorageBackend(Files.createTempDirectory("guardian-activity").resolve("audit.sqlite")), DuckDbStorageBackend(Files.createTempDirectory("guardian-activity-duck").resolve("audit.duckdb"))).forEach { db -> db.use { it.open();test(it) } }
    }
    @Test fun activityKindsFiltersNamesAndPaginationAreIndependent() = stores { db ->
        val login = row(PlayerActivityKind.LOGIN, 10)
        val chat = row(PlayerActivityKind.CHAT, 20, text = "Unicode coal ☃ ' SQL --")
        val logout = row(PlayerActivityKind.LOGOUT, 30, "RenamedPlayer")
        db.append(listOf(login, chat, logout, chat))
        assertEquals(listOf(chat),db.lookupActivity(query(PlayerActivityKind.CHAT)))
        val all = query(*PlayerActivityKind.entries.toTypedArray())
        assertEquals(listOf(logout,chat,login), db.lookupActivity(all))
        assertEquals(listOf(chat),db.lookupActivity(all.copy(limit = 1,offset = 1)))
        assertEquals(listOf(login,chat,logout),db.lookupActivity(all.copy(oldestFirst = true)))
        assertEquals(listOf(chat,login),db.lookupActivity(all.copy(playerName = "playerone")))
        assertEquals(listOf(logout,chat),db.lookupActivity(all.copy(afterEpochMillis = 20)))
        assertTrue(db.lookupActivity(all.copy(playerName = "' OR 1=1 --")).isEmpty())
        assertTrue(db.lookupActivity(all.copy(playerUuid = UUID.randomUUID())).isEmpty())
    }
    @Test fun snapshotsRejectMissingChatTextAndSessionPayloads() {
        assertFailsWith<IllegalArgumentException> { row(PlayerActivityKind.CHAT,1) }
        assertFailsWith<IllegalArgumentException> { row(PlayerActivityKind.CHAT,1,text = "a".repeat(4097)) }
        assertFailsWith<IllegalArgumentException> { row(PlayerActivityKind.LOGIN,1,text = "private") }
        assertFailsWith<IllegalArgumentException> { PlayerActivityQuery(emptySet()) }
        assertFailsWith<IllegalArgumentException> { query(PlayerActivityKind.CHAT).copy(offset = -1) }
    }
    @Test fun sqliteAndDuckDbActivitySurvivesReopen() {
        for (duck in listOf(false,true)) {
            val file = Files.createTempDirectory("guardian-activity-reopen").resolve("history.db")
            fun backend(): QueryableStorageBackend = if (duck) DuckDbStorageBackend(file) else SqliteStorageBackend(file)
            val chat = row(PlayerActivityKind.CHAT,1,text = "kept across restart")
            backend().use { it.open();it.append(listOf(chat));it.flush() }
            backend().use { it.open();assertEquals(listOf(chat),it.lookupActivity(query(PlayerActivityKind.CHAT))) }
        }
    }
    @Test fun schemaTenUpgradeAddsActivityWithoutReplacingExistingTables() {
        val file = Files.createTempDirectory("guardian-activity-upgrade").resolve("history.sqlite")
        SqliteStorageBackend(file).use { it.open() }
        DriverManager.getConnection("jdbc:sqlite:$file").use { c -> c.createStatement().use {
            it.executeUpdate("DROP TABLE ex_container_item");it.executeUpdate("DROP TABLE ex_player_activity")
            it.executeUpdate("DELETE FROM ex_schema_migrations WHERE version >= 11")
            it.executeUpdate("UPDATE ex_meta SET meta_value = '10' WHERE meta_key = 'schema_version'")
            it.executeUpdate("INSERT INTO ex_meta(meta_key,meta_value) VALUES ('activity_upgrade_sentinel','retained')")
        } }
        SqliteStorageBackend(file).use { it.open();assertEquals(GuardianSchema.CURRENT_VERSION,it.health().schemaVersion);assertTrue(it.lookupActivity(query(PlayerActivityKind.CHAT)).isEmpty()) }
        DriverManager.getConnection("jdbc:sqlite:$file").use { c -> c.createStatement().use { s -> s.executeQuery("SELECT meta_value FROM ex_meta WHERE meta_key = 'activity_upgrade_sentinel'").use { r -> assertTrue(r.next());assertEquals("retained",r.getString(1)) } } }
    }
}
