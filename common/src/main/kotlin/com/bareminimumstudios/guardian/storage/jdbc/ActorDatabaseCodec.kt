package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.domain.ActorIdentity
import com.bareminimumstudios.guardian.domain.ResourceId
import java.sql.Connection
import java.util.UUID

internal class ActorDatabaseCodec(
    private val allocator: SequenceAllocator
) {
    private val idCache = BoundedLruCache<String, Long>(4_096)
    private val actorCache = BoundedLruCache<Long, ActorIdentity>(4_096)

    fun idFor(connection: Connection, actor: ActorIdentity): Long? {
        if (actor === ActorIdentity.Unknown) return null
        val fingerprint = fingerprint(actor)
        idCache[fingerprint]?.let { id ->
            if (actorCache[id] != actor) {
                refreshMutableFields(connection, id, actor)
                actorCache[id] = actor
            }
            return id
        }

        val existing = connection.prepareStatement(
            "SELECT id FROM ex_actor_map WHERE fingerprint = ?"
        ).use { statement ->
            statement.setString(1, fingerprint)
            statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
        }

        if (existing != null) {
            idCache[fingerprint] = existing
            refreshMutableFields(connection, existing, actor)
            actorCache[existing] = actor
            return existing
        }

        val id = allocator.next(connection, "actor")
        connection.prepareStatement(
            """
            INSERT INTO ex_actor_map(id, kind, uuid, name, entity_type, source, fingerprint)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, id)
            when (actor) {
                is ActorIdentity.Player -> {
                    statement.setInt(2, KIND_PLAYER)
                    statement.setString(3, actor.uuid.toString())
                    statement.setString(4, actor.lastKnownName)
                    statement.setString(5, null)
                    statement.setString(6, null)
                }
                is ActorIdentity.Entity -> {
                    statement.setInt(2, KIND_ENTITY)
                    statement.setString(3, actor.uuid?.toString())
                    statement.setString(4, null)
                    statement.setString(5, actor.entityType.toString())
                    statement.setString(6, null)
                }
                is ActorIdentity.System -> {
                    statement.setInt(2, KIND_SYSTEM)
                    statement.setString(3, null)
                    statement.setString(4, null)
                    statement.setString(5, null)
                    statement.setString(6, actor.source)
                }
                ActorIdentity.Unknown -> error("Unknown actors are represented as NULL and are never inserted")
            }
            statement.setString(7, fingerprint)
            statement.executeUpdate()
        }
        idCache[fingerprint] = id
        actorCache[id] = actor
        return id
    }

    fun actorFor(connection: Connection, id: Long?): ActorIdentity {
        if (id == null) return ActorIdentity.Unknown
        actorCache[id]?.let { return it }
        val actor = connection.prepareStatement(
            "SELECT kind, uuid, name, entity_type, source FROM ex_actor_map WHERE id = ?"
        ).use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { result ->
                check(result.next()) { "Missing actor mapping id=$id" }
                when (result.getInt("kind")) {
                    KIND_PLAYER -> ActorIdentity.Player(
                        UUID.fromString(result.getString("uuid")),
                        result.getString("name")
                    )
                    KIND_ENTITY -> ActorIdentity.Entity(
                        result.getString("uuid")?.let(UUID::fromString),
                        ResourceId.parse(result.getString("entity_type"))
                    )
                    KIND_SYSTEM -> ActorIdentity.System(result.getString("source"))
                    else -> error("Unknown actor kind ${result.getInt("kind")} for id=$id")
                }
            }
        }
        actorCache[id] = actor
        return actor
    }

    fun clear() {
        idCache.clear()
        actorCache.clear()
    }

    private fun refreshMutableFields(connection: Connection, id: Long, actor: ActorIdentity) {
        if (actor !is ActorIdentity.Player || actor.lastKnownName == null) return
        connection.prepareStatement("UPDATE ex_actor_map SET name = ? WHERE id = ? AND (name IS NULL OR name <> ?)").use { statement ->
            statement.setString(1, actor.lastKnownName)
            statement.setLong(2, id)
            statement.setString(3, actor.lastKnownName)
            statement.executeUpdate()
        }
    }

    private fun fingerprint(actor: ActorIdentity): String = when (actor) {
        is ActorIdentity.Player -> "player:${actor.uuid}"
        is ActorIdentity.Entity -> "entity:${actor.uuid ?: "-"}:${actor.entityType}"
        is ActorIdentity.System -> "system:${actor.source}"
        ActorIdentity.Unknown -> "unknown"
    }

    private companion object {
        const val KIND_PLAYER = 1
        const val KIND_ENTITY = 2
        const val KIND_SYSTEM = 3
    }
}
