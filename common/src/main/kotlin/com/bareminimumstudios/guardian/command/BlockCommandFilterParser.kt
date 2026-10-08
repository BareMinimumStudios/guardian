package com.bareminimumstudios.guardian.command

import com.bareminimumstudios.guardian.domain.ActionType
import com.bareminimumstudios.guardian.domain.BlockPosition

object BlockCommandFilterParser {
    fun parse(raw: String): Result<BlockCommandFilter> = runCatching {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return@runCatching BlockCommandFilter()

        var actorName: String? = null
        var lookbackMillis: Long? = null
        var radius: Int? = null
        var useWorldEditSelection = false
        var x: Int? = null
        var y: Int? = null
        var z: Int? = null
        var actions = emptySet<ActionType>()
        var limit: Int? = null
        var page: Int? = null
        var order: String? = null

        for (token in trimmed.split(Regex("\\s+"))) {
            val separator = token.indexOf(':')
            require(separator > 0 && separator < token.lastIndex) { "Invalid parameter '$token'. Expected key:value." }
            val key = token.substring(0, separator).lowercase()
            val value = token.substring(separator + 1)
            when (key) {
                "u", "user" -> {
                    require(actorName == null) { "Player filter was specified more than once." }
                    require(PLAYER_NAME.matches(value)) { "Invalid player name '$value'." }
                    actorName = value
                }
                "t", "time" -> {
                    require(lookbackMillis == null) { "Time filter was specified more than once." }
                    lookbackMillis = parseDurationMillis(value)
                }
                "r", "radius" -> {
                    require(radius == null && !useWorldEditSelection) { "Radius was specified more than once." }
                    when (value.lowercase()) {
                        "#worldedit", "#we" -> useWorldEditSelection = true
                        else -> radius = value.toIntOrNull()?.also {
                            require(it in 0..10_000) { "Radius must be between 0 and 10,000." }
                        } ?: throw IllegalArgumentException("Invalid radius '$value'. Use a number, #worldedit, or #we.")
                    }
                }
                "x" -> x = parseCoordinate("x", value, x)
                "y" -> y = parseCoordinate("y", value, y)
                "z" -> z = parseCoordinate("z", value, z)
                "a", "action" -> {
                    require(actions.isEmpty()) { "Action filter was specified more than once." }
                    actions = parseActions(value)
                }
                "o", "order" -> {
                    require(order == null) { "Order was specified more than once." }
                    order = value.lowercase().also { require(it == "oldest" || it == "newest") { "Order must be oldest or newest." } }
                }
                "p", "page" -> {
                    require(page == null) { "Page was specified more than once." }
                    page = value.toIntOrNull()?.also { require(it in 1..10000) { "Page must be between 1 and 10,000." } }
                        ?: throw IllegalArgumentException("Invalid page '$value'.")
                }
                "l", "limit" -> {
                    require(limit == null) { "Limit was specified more than once." }
                    limit = value.toIntOrNull()?.also {
                        require(it in 1..10_000) { "Limit must be between 1 and 10,000." }
                    } ?: throw IllegalArgumentException("Invalid limit '$value'.")
                }
                else -> throw IllegalArgumentException("Unknown parameter '$key'.")
            }
        }

        val coordinateCount = listOf(x, y, z).count { it != null }
        require(coordinateCount == 0 || coordinateCount == 3) { "x:, y:, and z: must be supplied together." }
        require(!(useWorldEditSelection && coordinateCount != 0)) { "r:#worldedit cannot be combined with x:/y:/z:." }

        BlockCommandFilter(
            actorName = actorName,
            lookbackMillis = lookbackMillis,
            radius = radius,
            useWorldEditSelection = useWorldEditSelection,
            explicitPosition = if (coordinateCount == 3) BlockPosition(x!!, y!!, z!!) else null,
            actions = actions,
            limit = limit, page = page, oldestFirst = order == "oldest"
        )
    }

    private fun parseCoordinate(name: String, raw: String, existing: Int?): Int {
        require(existing == null) { "$name coordinate was specified more than once." }
        return raw.toIntOrNull() ?: throw IllegalArgumentException("Invalid $name coordinate '$raw'.")
    }

    private fun parseActions(raw: String): Set<ActionType> {
        val values = raw.split(',').filter(String::isNotBlank)
        require(values.isNotEmpty()) { "Action filter cannot be empty." }
        return values.flatMapTo(linkedSetOf()) { value ->
            when (value.lowercase()) {
                "block", "blocks" -> listOf(ActionType.BLOCK_PLACE, ActionType.BLOCK_BREAK, ActionType.BLOCK_CHANGE)
                "place", "+block", "+blocks" -> listOf(ActionType.BLOCK_PLACE)
                "break", "remove", "-block", "-blocks" -> listOf(ActionType.BLOCK_BREAK)
                "change" -> listOf(ActionType.BLOCK_CHANGE)
                else -> throw IllegalArgumentException("Unknown block action '$value'. Use place, break, change, or block.")
            }
        }
    }

    private fun parseDurationMillis(raw: String): Long {
        val normalized = raw.lowercase()
        require(normalized.isNotBlank()) { "Time value cannot be empty." }
        var cursor = 0
        var total = 0L
        for (match in DURATION_PART.findAll(normalized)) {
            require(match.range.first == cursor) { "Invalid time value '$raw'. Try values like 30m, 2h, or 1d12h." }
            cursor = match.range.last + 1
            val amount = match.groupValues[1].toLong()
            require(amount > 0L) { "Time parts must be greater than zero." }
            val multiplier = when (match.groupValues[2]) {
                "s" -> 1_000L
                "m" -> 60_000L
                "h" -> 3_600_000L
                "d" -> 86_400_000L
                "w" -> 604_800_000L
                else -> error("Unexpected duration unit")
            }
            total = Math.addExact(total, Math.multiplyExact(amount, multiplier))
        }
        require(cursor == normalized.length && total > 0L) {
            "Invalid time value '$raw'. Try values like 30m, 2h, or 1d12h."
        }
        return total
    }

    private val DURATION_PART = Regex("(\\d+)([smhdw])")
    private val PLAYER_NAME = Regex("[A-Za-z0-9_]{1,16}")
}
