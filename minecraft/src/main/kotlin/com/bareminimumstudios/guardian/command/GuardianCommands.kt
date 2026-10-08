package com.bareminimumstudios.guardian.command

import com.bareminimumstudios.guardian.GuardianRuntime
import com.bareminimumstudios.guardian.config.GuardianConfig
import com.bareminimumstudios.guardian.domain.BlockPosition
import com.bareminimumstudios.guardian.domain.BlockBounds
import com.bareminimumstudios.guardian.domain.ResourceId
import com.bareminimumstudios.guardian.integration.api.GuardianIntegrationApi
import com.bareminimumstudios.guardian.integration.api.RegionSelectionResult
import com.bareminimumstudios.guardian.lookup.BlockHistoryFormatter
import com.bareminimumstudios.guardian.lookup.BlockInspector
import com.bareminimumstudios.guardian.permission.PermissionService
import com.bareminimumstudios.guardian.storage.query.BlockLookupQuery
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.Commands
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth

object GuardianCommands {
    private const val LOOKUP_PERMISSION = "guardian.lookup"
    private const val INSPECT_PERMISSION = "guardian.inspect"
    private const val ROLLBACK_PERMISSION = "guardian.rollback"
    private const val STATUS_PERMISSION = "guardian.status"

    fun register(
        dispatcher: CommandDispatcher<CommandSourceStack>,
        permissions: PermissionService,
        runtimeProvider: () -> GuardianRuntime?,
        configProvider: () -> GuardianConfig
    ) {
            registerRoot(dispatcher, "guardian", permissions, runtimeProvider, configProvider)
            registerRoot(dispatcher, "co", permissions, runtimeProvider, configProvider)
    }

    private fun registerRoot(
        dispatcher: CommandDispatcher<CommandSourceStack>,
        name: String,
        permissions: PermissionService,
        runtimeProvider: () -> GuardianRuntime?,
        configProvider: () -> GuardianConfig
    ) {
        val root = Commands.literal(name)
            .executes { context ->
                context.source.sendSystemMessage(Component.literal("Guardian: use lookup/l, transactions, inspect/i, rollback/rb, or status."))
                1
            }

        root.then(
            lookupNode("lookup", permissions, runtimeProvider, configProvider)
        ).then(
            lookupNode("l", permissions, runtimeProvider, configProvider)
        ).then(
            inspectNode("inspect", permissions)
        ).then(
            inspectNode("i", permissions)
        ).then(
            rollbackNode("rollback", permissions, runtimeProvider, configProvider)
        ).then(
            rollbackNode("rb", permissions, runtimeProvider, configProvider)
        ).then(
            statusNode(permissions, runtimeProvider)
        )

        root.then(Commands.literal("transactions").requires { permissions.has(it, LOOKUP_PERMISSION, 2) }
            .then(Commands.argument("position", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes { context ->
                val history = runtimeProvider()?.history()
                if (history == null) { context.source.sendFailure(Component.literal("Guardian history is unavailable.")); return@executes 0 }
                val pos = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(context, "position")
                val query = com.bareminimumstudios.guardian.storage.query.ContainerLookupQuery(dimension(context.source), BlockPosition(pos.x, pos.y, pos.z))
                history.lookupContainers(query, { transactions ->
                    com.bareminimumstudios.guardian.lookup.ContainerHistoryFormatter.lines(transactions).forEach(context.source::sendSystemMessage)
                }, { context.source.sendFailure(Component.literal("Guardian container lookup failed; see server log.")) })
                1
            }))
        root.then(Commands.literal("transactions").requires { permissions.has(it, LOOKUP_PERMISSION, 2) }
            .then(Commands.literal("player").then(Commands.argument("name", StringArgumentType.word()).suggests { context, builder -> net.minecraft.commands.SharedSuggestionProvider.suggest(context.source.server.playerList.players.map { it.gameProfile.name }, builder) }.executes { context ->
                val history = runtimeProvider()?.history()
                if (history == null) { context.source.sendFailure(Component.literal("Guardian history is unavailable.")); return@executes 0 }
                val name = StringArgumentType.getString(context, "name")
                val uuid = runCatching { java.util.UUID.fromString(name) }.getOrNull()
                val query = com.bareminimumstudios.guardian.storage.query.ContainerLookupQuery(actorUuid = uuid, actorName = if (uuid == null) name else null)
                history.lookupContainers(query, { transactions ->
                    com.bareminimumstudios.guardian.lookup.ContainerHistoryFormatter.lines(transactions).forEach(context.source::sendSystemMessage)
                }, { context.source.sendFailure(Component.literal("Guardian item lookup failed; see server log.")) })
                1
            })))
        root.then(Commands.literal("transactions").requires { permissions.has(it, LOOKUP_PERMISSION, 2) }
            .executes { context -> context.source.sendSystemMessage(Component.literal("Usage: /guardian transactions u:<player> [t:1h] [l:20], or /guardian transactions <x> <y> <z>")); 0 }
            .then(Commands.argument("filters", StringArgumentType.greedyString())
                .suggests { context, builder -> suggestFilters(context.source, builder, true) }
                .executes { context -> executeTransactions(context.source, StringArgumentType.getString(context, "filters"), runtimeProvider(), configProvider()) }))
        dispatcher.register(root)
    }

    private fun lookupNode(
        name: String,
        permissions: PermissionService,
        runtimeProvider: () -> GuardianRuntime?,
        configProvider: () -> GuardianConfig
    ) = Commands.literal(name)
        .requires { permissions.has(it, LOOKUP_PERMISSION, 2) }
        .executes { context ->
            context.source.sendSystemMessage(
                Component.literal("Usage: /guardian lookup u:<player> t:<time> r:<radius|#worldedit> a:<place|break|change> [x:<x> y:<y> z:<z>] [l:<limit>]")
            )
            0
        }
        .then(
            Commands.argument("params", StringArgumentType.greedyString())
                .suggests { context, builder -> suggestFilters(context.source, builder) }
                .executes { context ->
                    executeLookup(
                        source = context.source,
                        raw = StringArgumentType.getString(context, "params"),
                        runtime = runtimeProvider(),
                        config = configProvider()
                    )
                }
        )

    private fun suggestFilters(source: CommandSourceStack, builder: com.mojang.brigadier.suggestion.SuggestionsBuilder, items: Boolean = false): java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> {
        val start = builder.input.lastIndexOf(' ').plus(1).coerceAtLeast(builder.start)
        val current = builder.input.substring(start)
        val choices = FilterSuggestions.values(current, source.server.playerList.players.map { it.gameProfile.name }, items)
        return net.minecraft.commands.SharedSuggestionProvider.suggest(choices, builder.createOffset(start))
    }

    private fun executeTransactions(source: CommandSourceStack, raw: String, runtime: GuardianRuntime?, config: GuardianConfig): Int {
        val history = runtime?.history() ?: run { source.sendFailure(Component.literal("Guardian history is unavailable.")); return 0 }
        val filter = BlockCommandFilterParser.parse(raw).getOrElse { source.sendFailure(Component.literal("Guardian transactions: ${it.message}")); return 0 }
        if (filter.actions.isNotEmpty()) { source.sendFailure(Component.literal("Item history does not accept block action filters.")); return 0 }
        val scope = resolveScope(source, filter) ?: return 0
        val limit = filter.limit ?: config.lookup.defaultResults.get()
        if (limit > minOf(500, config.lookup.maxResults.get()) || (filter.radius ?: 0) > config.lookup.maxRadius.get()) {
            source.sendFailure(Component.literal("Guardian transaction limit/radius exceeds the configured maximum (limit at most 500).")); return 0
        }
        val profile = filter.actorName?.let { name -> source.server.playerList.players.firstOrNull { it.gameProfile.name.equals(name, ignoreCase = true) }?.gameProfile }
        val query = com.bareminimumstudios.guardian.storage.query.ContainerLookupQuery(
            dimension = if (filter.explicitPosition != null || filter.radius != null || scope.bounds != null) scope.dimension else null,
            position = filter.explicitPosition ?: filter.radius?.let { sourcePosition(source) },
            actorUuid = profile?.id, actorName = filter.actorName?.takeIf { profile == null }, limit = limit,
            afterEpochMillis = filter.lookbackMillis?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) },
            radius = filter.radius, bounds = scope.bounds, offset = ((filter.page ?: 1) - 1) * limit
        )
        history.lookupContainers(query, { rows ->
            com.bareminimumstudios.guardian.lookup.ContainerHistoryFormatter.lines(rows).forEach(source::sendSystemMessage)
            source.sendSystemMessage(Component.literal("Item history page ${filter.page ?: 1}, up to $limit transactions. Use p:${(filter.page ?: 1) + 1} for the next page (l: maximum 500)."))
        }, { source.sendFailure(Component.literal("Guardian item lookup failed; see server log.")) })
        return 1
    }

    private fun inspectNode(name: String, permissions: PermissionService) = Commands.literal(name)
        .requires { permissions.has(it, INSPECT_PERMISSION, 2) }
        .executes { context ->
            val player = context.source.player
            if (player == null) {
                context.source.sendFailure(Component.literal("Guardian inspector can only be used by a player."))
                return@executes 0
            }
            val enabled = BlockInspector.toggle(player)
            context.source.sendSystemMessage(Component.literal("Guardian inspector ${if (enabled) "enabled" else "disabled"}."))
            1
        }
        .then(
            Commands.literal("on").executes { context ->
                val player = context.source.player
                if (player == null) {
                    context.source.sendFailure(Component.literal("Guardian inspector can only be used by a player."))
                    return@executes 0
                }
                BlockInspector.set(player, true)
                context.source.sendSystemMessage(Component.literal("Guardian inspector enabled."))
                1
            }
        )
        .then(
            Commands.literal("off").executes { context ->
                val player = context.source.player
                if (player == null) {
                    context.source.sendFailure(Component.literal("Guardian inspector can only be used by a player."))
                    return@executes 0
                }
                BlockInspector.set(player, false)
                context.source.sendSystemMessage(Component.literal("Guardian inspector disabled."))
                1
            }
        )

    private fun rollbackNode(
        name: String,
        permissions: PermissionService,
        runtimeProvider: () -> GuardianRuntime?,
        configProvider: () -> GuardianConfig
    ) = Commands.literal(name)
        .requires { permissions.has(it, ROLLBACK_PERMISSION, 3) }
        .executes { context ->
            context.source.sendSystemMessage(
                Component.literal("Usage: /guardian rollback t:<time> [u:<player>] [r:<radius|#worldedit>] [a:<place|break|change>] [x:<x> y:<y> z:<z>]")
            )
            0
        }
        .then(
            Commands.argument("params", StringArgumentType.greedyString())
                .suggests { context, builder -> suggestFilters(context.source, builder) }
                .executes { context ->
                    executeRollback(
                        source = context.source,
                        raw = StringArgumentType.getString(context, "params"),
                        runtime = runtimeProvider(),
                        config = configProvider()
                    )
                }
        )


    private fun statusNode(
        permissions: PermissionService,
        runtimeProvider: () -> GuardianRuntime?
    ) = Commands.literal("status")
        .requires { permissions.has(it, STATUS_PERMISSION, 2) }
        .executes { context ->
            val runtime = runtimeProvider()
            val pipeline = runtime?.pipeline()
            val storage = runtime?.storage()
            if (runtime == null || pipeline == null || storage == null) {
                context.source.sendFailure(Component.literal("Guardian runtime is not active."))
                return@executes 0
            }
            val p = pipeline.metrics()
            val b = runtime.bulk()?.metrics()
            val h = storage.health()
            context.source.sendSystemMessage(
                Component.literal(
                    "Guardian ${h.backendId} schema=${h.schemaVersion} | writer=${p.state} queued=${p.queued} " +
                        "accepted=${p.accepted} persisted=${p.persisted} backpressure=${p.backpressure} failures=${p.writeFailures}"
                )
            )
            context.source.sendSystemMessage(Component.literal(com.bareminimumstudios.guardian.platform.minecraft.PlayerContainerCapture.status()))
            context.source.sendSystemMessage(Component.literal(com.bareminimumstudios.guardian.platform.minecraft.HopperTransferCapture.status()))
            if (b != null) {
                context.source.sendSystemMessage(
                    Component.literal(
                        "Bulk audit: reserved=${b.reservedOperations} queuedOps=${b.queuedOperations} queuedEntries=${b.queuedEntries} " +
                            "chunks=${b.streamedChunks} submitted=${b.submittedEntries} retries=${b.backpressureRetries} rejected=${b.rejectedReservations} failed=${b.failed}"
                    )
                )
            }
            1
        }

    private fun executeLookup(
        source: CommandSourceStack,
        raw: String,
        runtime: GuardianRuntime?,
        config: GuardianConfig
    ): Int {
        val history = runtime?.history()
        if (history == null) {
            source.sendFailure(Component.literal("Guardian storage/history is not running."))
            return 0
        }

        val filter = BlockCommandFilterParser.parse(raw).getOrElse {
            source.sendFailure(Component.literal("Guardian lookup: ${it.message}"))
            return 0
        }
        val radius = filter.radius
        if (radius != null && radius > config.lookup.maxRadius.get()) {
            source.sendFailure(Component.literal("Guardian lookup radius exceeds the configured maximum of ${config.lookup.maxRadius.get()}."))
            return 0
        }
        val scope = resolveScope(source, filter) ?: return 0
        val requestedLimit = filter.limit ?: config.lookup.defaultResults.get()
        if (requestedLimit > config.lookup.maxResults.get()) {
            source.sendFailure(Component.literal("Guardian lookup limit exceeds the configured maximum of ${config.lookup.maxResults.get()}."))
            return 0
        }

        val position = when {
            scope.bounds != null -> null
            filter.explicitPosition != null -> filter.explicitPosition
            radius != null -> sourcePosition(source)
            else -> null
        }
        val now = System.currentTimeMillis()
        val query = BlockLookupQuery(
            dimension = scope.dimension,
            position = position,
            bounds = scope.bounds,
            radius = radius,
            actorUuid = filter.actorName?.let { source.server.playerList.players.firstOrNull { player -> player.gameProfile.name.equals(it, ignoreCase = true) }?.uuid },
            actorName = filter.actorName?.takeIf { source.server.playerList.players.none { player -> player.gameProfile.name.equals(it, ignoreCase = true) } },
            actions = filter.actions,
            afterEpochMillis = filter.lookbackMillis?.let { (now - it).coerceAtLeast(0L) },
            limit = requestedLimit, offset = ((filter.page ?: 1) - 1) * requestedLimit
        )

        source.sendSystemMessage(Component.literal("Guardian: searching block history..."))
        history.lookup(
            query,
            onSuccess = { rows ->
                BlockHistoryFormatter.lines(rows).forEach(source::sendSystemMessage)
                source.sendSystemMessage(Component.literal("Scope: ${scope.dimension}; page ${filter.page ?: 1}, up to $requestedLimit block records. Use p:${(filter.page ?: 1) + 1} for the next page or l:<limit> to show more. Item history: /guardian transactions u:<player>."))
            },
            onFailure = { source.sendFailure(Component.literal("Guardian lookup failed; see the server log.")) }
        )
        return 1
    }

    private fun executeRollback(
        source: CommandSourceStack,
        raw: String,
        runtime: GuardianRuntime?,
        config: GuardianConfig
    ): Int {
        val rollback = runtime?.rollback()
        if (rollback == null) {
            source.sendFailure(Component.literal("Guardian rollback service is not running."))
            return 0
        }

        val filter = BlockCommandFilterParser.parse(raw).getOrElse {
            source.sendFailure(Component.literal("Guardian rollback: ${it.message}"))
            return 0
        }
        val lookbackMillis = filter.lookbackMillis ?: run {
            source.sendFailure(Component.literal("Guardian rollback requires a t:<time> filter for safety."))
            return 0
        }
        if (filter.limit != null || filter.page != null) {
            source.sendFailure(Component.literal("Guardian rollback does not accept l: or p:. Narrow the rollback with t:, r:, or u: instead."))
            return 0
        }

        val scope = resolveScope(source, filter) ?: return 0
        val radius = if (scope.bounds == null) (filter.radius ?: config.rollback.defaultRadius.get()) else null
        if (radius != null && radius > config.rollback.maxRadius.get()) {
            source.sendFailure(Component.literal("Guardian rollback radius exceeds the configured maximum of ${config.rollback.maxRadius.get()}."))
            return 0
        }
        val center = if (scope.bounds == null) (filter.explicitPosition ?: sourcePosition(source)) else null
        val now = System.currentTimeMillis()
        val query = BlockLookupQuery(
            dimension = scope.dimension,
            position = center,
            bounds = scope.bounds,
            radius = radius,
            actorUuid = filter.actorName?.let { source.server.playerList.players.firstOrNull { player -> player.gameProfile.name.equals(it, ignoreCase = true) }?.uuid },
            actorName = filter.actorName?.takeIf { source.server.playerList.players.none { player -> player.gameProfile.name.equals(it, ignoreCase = true) } },
            actions = filter.actions,
            afterEpochMillis = (now - lookbackMillis).coerceAtLeast(0L),
            includeRolledBack = false,
            limit = (config.rollback.maxRecords.get() + 1).coerceAtMost(10_000)
        )

        val description = if (scope.bounds != null) {
            val b = scope.bounds
            "filter=[$raw], selection=${b.min.x},${b.min.y},${b.min.z}..${b.max.x},${b.max.y},${b.max.z}"
        } else {
            "filter=[$raw], center=${center!!.x},${center.y},${center.z}, radius=$radius"
        }
        return if (rollback.request(source, query, description)) 1 else 0
    }

    private fun resolveScope(source: CommandSourceStack, filter: BlockCommandFilter): QueryScope? {
        if (!filter.useWorldEditSelection) return QueryScope(dimension(source), null)
        val player = source.player
        if (player == null) {
            source.sendFailure(Component.literal("r:#worldedit requires a player command source."))
            return null
        }
        val provider = GuardianIntegrationApi.regionSelectionProvider()
        if (provider == null) {
            source.sendFailure(Component.literal("WorldEdit selection integration is unavailable or disabled."))
            return null
        }
        return when (val result = provider.selectionFor(player)) {
            is RegionSelectionResult.Success -> QueryScope(result.selection.dimension, result.selection.bounds)
            is RegionSelectionResult.Failure -> {
                source.sendFailure(Component.literal(result.message))
                null
            }
        }
    }

    private data class QueryScope(val dimension: ResourceId, val bounds: BlockBounds?)

    private fun dimension(source: CommandSourceStack): ResourceId =
        ResourceId.parse(source.level.dimension().location().toString())

    private fun sourcePosition(source: CommandSourceStack): BlockPosition = BlockPosition(
        Mth.floor(source.position.x),
        Mth.floor(source.position.y),
        Mth.floor(source.position.z)
    )
}
