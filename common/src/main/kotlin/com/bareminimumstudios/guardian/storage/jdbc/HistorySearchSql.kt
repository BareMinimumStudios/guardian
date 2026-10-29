package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.storage.query.*

internal data class HistorySearchSql(val clause: String, val values: List<String>) {
    companion object {
        fun activityActors(query:PlayerActivityQuery):HistorySearchSql {
            if(com.bareminimumstudios.guardian.domain.PlayerActivityKind.WHISPER !in query.kinds)return actors(query.search,"player_uuid","player_name")
            val conditions=mutableListOf<String>();val values=mutableListOf<String>()
            fun match(players:List<HistoryPlayer>):String {
                val f=HistorySearchFilter(players=players)
                val a=actors(f,"player_uuid","player_name");val b=actors(f,"recipient_uuid","recipient_name","kind = 'WHISPER'")
                values.addAll(a.values);values.addAll(b.values)
                return "("+a.clause.removePrefix(" AND ")+" OR "+b.clause.removePrefix(" AND ")+")"
            }
            if(query.search.players.isNotEmpty())conditions.add(match(query.search.players))
            if(query.search.excludedPlayers.isNotEmpty())conditions.add("NOT "+match(query.search.excludedPlayers))
            return HistorySearchSql(conditions.joinToString(" AND ",prefix=if(conditions.isEmpty())"" else " AND "),values)
        }

        fun actors(filter: HistorySearchFilter, uuid: String, name: String, playerGuard: String = "1=1"): HistorySearchSql {
            val values = mutableListOf<String>(); val conditions = mutableListOf<String>()
            fun selector(p: HistoryPlayer): String {
                values.add(p.uuid?.toString() ?: p.name)
                return if (p.uuid != null) "$uuid = ?" else "LOWER($name) = LOWER(?)"
            }
            if (filter.players.isNotEmpty()) conditions.add("COALESCE(($playerGuard AND (${filter.players.joinToString(" OR ", transform = ::selector)})), FALSE)")
            if (filter.excludedPlayers.isNotEmpty()) conditions.add("NOT COALESCE(($playerGuard AND (${filter.excludedPlayers.joinToString(" OR ", transform = ::selector)})), FALSE)")
            return HistorySearchSql(conditions.joinToString(" AND ", prefix = if (conditions.isEmpty()) "" else " AND "), values)
        }
        fun resources(filter: HistorySearchFilter, predicate: (Int) -> String): HistorySearchSql {
            val values = mutableListOf<String>();val conditions = mutableListOf<String>()
            if (filter.included.isNotEmpty()) { conditions.add(predicate(filter.included.size));values.addAll(filter.included.map { it.toString() }) }
            if (filter.excluded.isNotEmpty()) { conditions.add("NOT (${predicate(filter.excluded.size)})");values.addAll(filter.excluded.map { it.toString() }) }
            return HistorySearchSql(conditions.joinToString(" AND ", prefix = if (conditions.isEmpty()) "" else " AND "),values)
        }
    }
}
