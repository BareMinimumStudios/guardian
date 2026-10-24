package com.bareminimumstudios.guardian.command

import com.bareminimumstudios.guardian.rollback.BlockHistoryOperation
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
                context.source.sendSystemMessage(Component.literal("Guardian: use lookup/l, transactions, chat, sessions, commands, entities, fluids, inspect/i, rollback/rb, restore/redo, purge, rollback-items preview, or status."))
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
            rollbackNode("restore", permissions, runtimeProvider, configProvider, BlockHistoryOperation.RESTORE)
        ).then(
            rollbackNode("redo", permissions, runtimeProvider, configProvider, BlockHistoryOperation.RESTORE)
        ).then(
            statusNode(permissions, runtimeProvider)
        )

        root.then(Commands.literal("entities").requires { entityAuthorized(it,permissions) }
            .executes { context -> executeEntities(context.source,"",runtimeProvider(),configProvider()) }
            .then(Commands.argument("filters",StringArgumentType.greedyString())
                .suggests { context,builder ->
                    if (!entityAuthorized(context.source,permissions)) com.mojang.brigadier.suggestion.Suggestions.empty()
                    else {
                        val start=builder.input.lastIndexOf(' ').plus(1).coerceAtLeast(builder.start)
                        val token=builder.input.substring(start)
                        val choices=when(token.substringBefore(':').lowercase()) {
                            "a","action" -> listOf("${token.substringBefore(':')}:death","${token.substringBefore(':')}:rename","${token.substringBefore(':')}:tame","${token.substringBefore(':')}:breed","${token.substringBefore(':')}:frame","${token.substringBefore(':')}:frame-place","${token.substringBefore(':')}:frame-remove","${token.substringBefore(':')}:frame-change","${token.substringBefore(':')}:death,rename").filter { it.startsWith(token,true) }
                            else -> FilterSuggestions.values(token,context.source.server.playerList.players.map { it.gameProfile.name },true,net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.keySet().map { it.toString() }) +
                                if (':' !in token) listOf("a:","id:").filter { it.startsWith(token,true) } else emptyList()
                        }
                        net.minecraft.commands.SharedSuggestionProvider.suggest(choices,builder.createOffset(start))
                    }
                }
                .executes { context -> executeEntities(context.source,StringArgumentType.getString(context,"filters"),runtimeProvider(),configProvider()) }))
        root.then(Commands.literal("fluids").requires { fluidAuthorized(it,permissions) }
            .executes { context -> executeFluids(context.source,"",runtimeProvider(),configProvider()) }
            .then(Commands.argument("filters",StringArgumentType.greedyString())
                .suggests { context,builder ->
                    if (!fluidAuthorized(context.source,permissions)) com.mojang.brigadier.suggestion.Suggestions.empty()
                    else {
                        val start=builder.input.lastIndexOf(' ').plus(1).coerceAtLeast(builder.start)
                        val token=builder.input.substring(start)
                        val choices=when(token.substringBefore(':').lowercase()) {
                            "a","action" -> listOf("${token.substringBefore(':')}:place","${token.substringBefore(':')}:remove","${token.substringBefore(':')}:flow","${token.substringBefore(':')}:form").filter { it.startsWith(token,true) }
                            else -> FilterSuggestions.values(token,context.source.server.playerList.players.map { it.gameProfile.name },true,net.minecraft.core.registries.BuiltInRegistries.FLUID.keySet().map { it.toString() }) +
                                if (':' !in token) listOf("a:").filter { it.startsWith(token,true) } else emptyList()
                        }
                        net.minecraft.commands.SharedSuggestionProvider.suggest(choices,builder.createOffset(start))
                    }
                }
                .executes { context -> executeFluids(context.source,StringArgumentType.getString(context,"filters"),runtimeProvider(),configProvider()) }))
        listOf("chat", "sessions", "commands").forEach { name ->
            val permission = "guardian.$name"
            root.then(Commands.literal(name).requires { activityAuthorized(it, permissions, name) }
                .executes { context -> executeActivity(context.source, "", name, runtimeProvider(), configProvider()) }
                .then(Commands.argument("filters", StringArgumentType.greedyString())
                    .suggests { context, builder -> suggestFilters(activitySource(context.source,name), builder, permissions, activity = true, permission = permission, fallback = 3) }
                    .executes { context -> executeActivity(context.source, StringArgumentType.getString(context, "filters"), name, runtimeProvider(), configProvider()) }))
        }
        root.then(Commands.literal("transactions").requires { permissions.has(it, LOOKUP_PERMISSION, 2) }
            .then(Commands.argument("position", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes { context ->
                val history = runtimeProvider()?.history()
                if (history == null) { context.source.sendFailure(Component.literal("Guardian history is unavailable.")); return@executes 0 }
                val pos = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(context, "position")
                val query = com.bareminimumstudios.guardian.storage.query.ContainerLookupQuery(dimension(context.source), BlockPosition(pos.x, pos.y, pos.z))
                history.lookupContainers(query, { transactions ->
                    if (!authorized(context.source, permissions, LOOKUP_PERMISSION, 2)) return@lookupContainers
                    com.bareminimumstudios.guardian.lookup.ContainerHistoryFormatter.lines(transactions).forEach(context.source::sendSystemMessage)
                }, { if (authorized(context.source, permissions, LOOKUP_PERMISSION, 2)) context.source.sendFailure(Component.literal("Guardian container lookup failed; see server log.")) })
                1
            }))
        root.then(Commands.literal("transactions").requires { permissions.has(it, LOOKUP_PERMISSION, 2) }
            .then(Commands.literal("player").then(Commands.argument("name", StringArgumentType.word()).suggests { context, builder -> if (authorized(context.source, permissions, LOOKUP_PERMISSION, 2)) net.minecraft.commands.SharedSuggestionProvider.suggest(context.source.server.playerList.players.map { it.gameProfile.name }, builder) else com.mojang.brigadier.suggestion.Suggestions.empty() }.executes { context ->
                val history = runtimeProvider()?.history()
                if (history == null) { context.source.sendFailure(Component.literal("Guardian history is unavailable.")); return@executes 0 }
                val name = StringArgumentType.getString(context, "name")
                val uuid = runCatching { java.util.UUID.fromString(name) }.getOrNull()
                val query = com.bareminimumstudios.guardian.storage.query.ContainerLookupQuery(actorUuid = uuid, actorName = if (uuid == null) name else null)
                history.lookupContainers(query, { transactions ->
                    if (!authorized(context.source, permissions, LOOKUP_PERMISSION, 2)) return@lookupContainers
                    com.bareminimumstudios.guardian.lookup.ContainerHistoryFormatter.lines(transactions).forEach(context.source::sendSystemMessage)
                }, { if (authorized(context.source, permissions, LOOKUP_PERMISSION, 2)) context.source.sendFailure(Component.literal("Guardian item lookup failed; see server log.")) })
                1
            })))
        root.then(Commands.literal("transactions").requires { permissions.has(it, LOOKUP_PERMISSION, 2) }
            .executes { context -> context.source.sendSystemMessage(Component.literal("Usage: /guardian transactions u:<player> [t:1h] [l:20], or /guardian transactions <x> <y> <z>")); 0 }
            .then(Commands.argument("filters", StringArgumentType.greedyString())
                .suggests { context, builder -> suggestFilters(context.source, builder, permissions, true) }
                .executes { context -> executeTransactions(context.source, StringArgumentType.getString(context, "filters"), runtimeProvider(), configProvider()) }))
        root.then(Commands.literal("rollback-items").requires { permissions.has(it, ROLLBACK_PERMISSION, 3) }
            .executes { context -> context.source.sendSystemMessage(Component.literal("Use /guardian rollback-items preview t:1h r:10, recovery [operation UUID], or cancel. These commands are read-only.")); 0 }
            .then(Commands.literal("cancel").executes { context ->
                val runtime=runtimeProvider()
                val previewCancelled=runtime?.itemRollbackPreview()?.cancel() ?: false
                val recoveryCancelled=runtime?.itemRecovery()?.cancel() ?: false
                context.source.sendSystemMessage(Component.literal(if(previewCancelled || recoveryCancelled)
                    "Guardian: item check cancelled. No items changed or recovery claims cleared. Started reads may still finish."
                    else "Guardian: no item preview or recovery check is in progress."))
                if(previewCancelled || recoveryCancelled) 1 else 0
            })
            .then(Commands.literal("preview")
                .executes { context -> context.source.sendSystemMessage(Component.literal("Usage: /guardian rollback-items preview t:1h r:10. Preview only; no items change.")); 0 }
                .then(Commands.argument("filters",StringArgumentType.greedyString()).suggests { context,builder -> suggestFilters(context.source,builder,permissions,true,true,ROLLBACK_PERMISSION,3) }
                    .executes { context -> executeItemPreview(context.source,StringArgumentType.getString(context,"filters"),runtimeProvider(),configProvider()) }))
            .then(Commands.literal("recovery")
                .executes { context -> executeRecovery(context.source,null,runtimeProvider()) }
                .then(Commands.literal("saved")
                    .executes { context -> context.source.sendSystemMessage(Component.literal("Usage: /guardian rollback-items recovery saved <operation UUID>. Read-only saved block/player slots."));0 }
                    .then(Commands.argument("operation",StringArgumentType.word())
                        .suggests { context,builder -> if (authorized(context.source, permissions, ROLLBACK_PERMISSION, 3)) net.minecraft.commands.SharedSuggestionProvider.suggest(runtimeProvider()?.itemRecovery()?.suggestions(context.source) ?: emptyList(),builder) else com.mojang.brigadier.suggestion.Suggestions.empty() }
                        .executes { context -> executeRecovery(context.source,StringArgumentType.getString(context,"operation"),runtimeProvider(),true) }))
                .then(Commands.argument("operation",StringArgumentType.word())
                    .suggests { context,builder -> if (authorized(context.source, permissions, ROLLBACK_PERMISSION, 3)) net.minecraft.commands.SharedSuggestionProvider.suggest(runtimeProvider()?.itemRecovery()?.suggestions(context.source) ?: emptyList(),builder) else com.mojang.brigadier.suggestion.Suggestions.empty() }
                    .executes { context -> executeRecovery(context.source,StringArgumentType.getString(context,"operation"),runtimeProvider()) })))
        root.then(Commands.literal("purge").requires { authorized(it.player?.createCommandSourceStack() ?: it,permissions,"guardian.purge",4) }
            .executes { context -> context.source.sendSystemMessage(Component.literal("Usage: /guardian purge preview t:30d | confirm <token> | cancel. Permanent history deletion, at most 1000 records per confirmation."));0 }
            .then(Commands.literal("preview").then(Commands.argument("age",StringArgumentType.greedyString())
                .suggests { context,builder -> if(authorized(context.source.player?.createCommandSourceStack() ?: context.source,permissions,"guardian.purge",4))net.minecraft.commands.SharedSuggestionProvider.suggest(listOf("t:1d","t:7d","t:30d","t:90d"),builder)else com.mojang.brigadier.suggestion.Suggestions.empty() }
                .executes { context ->
                    val source=context.source;val raw=StringArgumentType.getString(context,"age")
                    if(!raw.matches(Regex("(?:t|time):[^\\s]+"))){source.sendFailure(Component.literal("Guardian purge accepts only one age filter, for example t:30d."));return@executes 0}
                    val age=BlockCommandFilterParser.parse(raw).getOrNull()?.lookbackMillis
                    val purge=runtimeProvider()?.purge()
                    if(age==null||purge==null){source.sendFailure(Component.literal("Guardian purge age is invalid or its service is unavailable."));0}else if(purge.preview(source,age))1 else 0
                }))
            .then(Commands.literal("confirm").then(Commands.argument("token",StringArgumentType.word())
                .suggests { context,builder -> net.minecraft.commands.SharedSuggestionProvider.suggest(runtimeProvider()?.purge()?.suggestions(context.source) ?: emptyList(),builder) }
                .executes { context -> if(runtimeProvider()?.purge()?.confirm(context.source,StringArgumentType.getString(context,"token"))==true)1 else 0 }))
            .then(Commands.literal("cancel").executes { context -> if(runtimeProvider()?.purge()?.cancel(context.source)==true)1 else 0 }))
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
                .suggests { context, builder -> suggestFilters(context.source, builder, permissions) }
                .executes { context ->
                    executeLookup(
                        source = context.source,
                        raw = StringArgumentType.getString(context, "params"),
                        runtime = runtimeProvider(),
                        config = configProvider()
                    )
                }
        )

    private fun suggestFilters(source: CommandSourceStack, builder: com.mojang.brigadier.suggestion.SuggestionsBuilder, permissions: PermissionService, items: Boolean = false, preview: Boolean = false, permission: String = LOOKUP_PERMISSION, fallback: Int = 2, activity: Boolean = false, extended: Boolean = true): java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> {
        if (!authorized(source, permissions, permission, fallback)) return com.mojang.brigadier.suggestion.Suggestions.empty()
        val start = builder.input.lastIndexOf(' ').plus(1).coerceAtLeast(builder.start)
        val current = builder.input.substring(start)
        val players=source.server.playerList.players.map { it.gameProfile.name }
        val resourceKey = current.substringBefore(':').lowercase()
        val resources = if (!extended || activity || resourceKey !in setOf("i", "include", "e", "exclude")) emptyList() else
            (if (items) net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet() else net.minecraft.core.registries.BuiltInRegistries.BLOCK.keySet()).map { it.toString() }
        val choices = if (preview) ItemRollbackPreviewFilters.suggestions(current,players) else FilterSuggestions.values(current, players, items, resources, extended)
        return net.minecraft.commands.SharedSuggestionProvider.suggest(choices.filter { !activity || it.substringBefore(':') in setOf("u", "user", "t", "time", "l", "limit", "p", "page", "o", "order") }, builder.createOffset(start))
    }

    private fun authorized(source: CommandSourceStack, permissions: PermissionService, permission: String, fallback: Int): Boolean =
        runCatching { permissions.has(source, permission, fallback) }.getOrDefault(false)

    private fun resolvedSearch(source: CommandSourceStack, filter: BlockCommandFilter) =
        filter.search.resolve(source.server.playerList.players.associate { it.gameProfile.name to it.uuid })

    private fun activitySource(source: CommandSourceStack, name: String): CommandSourceStack =
        if(name == "commands") source.player?.createCommandSourceStack() ?: source else source

    private fun activityAuthorized(source: CommandSourceStack, permissions: PermissionService, name: String): Boolean {
        if(name == "commands" && source.player?.let { source.server.playerList.getPlayer(it.uuid) !== it } == true)return false
        return authorized(activitySource(source,name),permissions,"guardian.$name",3)
    }

    private fun fluidAuthorized(source: CommandSourceStack, permissions: PermissionService): Boolean {
        if (source.player?.let { source.server.playerList.getPlayer(it.uuid) !== it } == true) return false
        return authorized(source.player?.createCommandSourceStack() ?: source,permissions,"guardian.fluids",2)
    }

    private fun executeFluids(source: CommandSourceStack, raw: String, runtime: GuardianRuntime?, config: GuardianConfig): Int {
        val history=runtime?.history() ?: run { source.sendFailure(Component.literal("Guardian history is unavailable."));return 0 }
        if (!fluidAuthorized(source,runtime.permissions)) return 0
        val parsed=FluidCommandFilters.parse(raw).getOrElse { source.sendFailure(Component.literal("Guardian fluids: ${it.message}"));return 0 }
        val filter=parsed.common
        val scope=resolveScope(source,filter) ?: return 0
        val limit=filter.limit ?: config.lookup.defaultResults.get()
        if (limit>minOf(500,config.lookup.maxResults.get()) || (filter.radius ?: 0)>config.lookup.maxRadius.get()) {
            source.sendFailure(Component.literal("Guardian fluid limit/radius exceeds the configured maximum."));return 0
        }
        val position=filter.explicitPosition ?: filter.radius?.let { sourcePosition(source) }
        val query=com.bareminimumstudios.guardian.storage.query.FluidLookupQuery(kinds=parsed.kinds,
            dimension=if(position!=null || scope.bounds!=null) scope.dimension else null,position=position,radius=filter.radius,bounds=scope.bounds,
            afterEpochMillis=filter.lookbackMillis?.let { (System.currentTimeMillis()-it).coerceAtLeast(0) },search=resolvedSearch(source,filter),
            limit=limit,offset=((filter.page ?: 1)-1)*limit,oldestFirst=filter.oldestFirst)
        val navigationRaw=if(filter.radius!=null && filter.explicitPosition==null) "$raw x:${position!!.x} y:${position.y} z:${position.z}" else raw
        history.lookupFluids(query,{ rows ->
            if (!fluidAuthorized(source,runtime.permissions)) return@lookupFluids
            source.sendSystemMessage(Component.literal("Guardian fluid history: ${rows.size} records (page ${filter.page ?: 1})."))
            rows.forEach { source.sendSystemMessage(com.bareminimumstudios.guardian.lookup.FluidHistoryFormatter.line(it)) }
            source.sendSystemMessage(com.bareminimumstudios.guardian.lookup.HistoryNavigation.footer(filter.page ?: 1,rows.size==limit,filter.oldestFirst,
                { page -> HistoryPageCommands.command("fluids",navigationRaw,page,filter.oldestFirst) },HistoryPageCommands.command("fluids",navigationRaw,1,!filter.oldestFirst)))
        },{ if(fluidAuthorized(source,runtime.permissions)) source.sendFailure(Component.literal("Guardian fluid lookup failed; see server log.")) })
        return 1
    }

    private fun entityAuthorized(source: CommandSourceStack, permissions: PermissionService): Boolean {
        if (source.player?.let { source.server.playerList.getPlayer(it.uuid) !== it } == true) return false
        return authorized(source.player?.createCommandSourceStack() ?: source,permissions,"guardian.entities",2)
    }

    private fun executeEntities(source: CommandSourceStack, raw: String, runtime: GuardianRuntime?, config: GuardianConfig): Int {
        val history=runtime?.history() ?: run { source.sendFailure(Component.literal("Guardian history is unavailable."));return 0 }
        if (!entityAuthorized(source,runtime.permissions)) return 0
        val parsed=EntityCommandFilters.parse(raw).getOrElse { source.sendFailure(Component.literal("Guardian entities: ${it.message}"));return 0 }
        val filter=parsed.common
        val scope=resolveScope(source,filter) ?: return 0
        val limit=filter.limit ?: config.lookup.defaultResults.get()
        if (limit>minOf(500,config.lookup.maxResults.get()) || (filter.radius ?: 0)>config.lookup.maxRadius.get()) {
            source.sendFailure(Component.literal("Guardian entity limit/radius exceeds the configured maximum."));return 0
        }
        val position=filter.explicitPosition ?: filter.radius?.let { sourcePosition(source) }
        val query=com.bareminimumstudios.guardian.storage.query.EntityLookupQuery(kinds=parsed.kinds,targetUuid=parsed.targetUuid,
            dimension=if(position!=null || scope.bounds!=null) scope.dimension else null,position=position,radius=filter.radius,bounds=scope.bounds,
            afterEpochMillis=filter.lookbackMillis?.let { (System.currentTimeMillis()-it).coerceAtLeast(0) },search=resolvedSearch(source,filter),
            limit=limit,offset=((filter.page ?: 1)-1)*limit,oldestFirst=filter.oldestFirst)
        val navigationRaw=if(filter.radius!=null && filter.explicitPosition==null) "$raw x:${position!!.x} y:${position.y} z:${position.z}" else raw
        history.lookupEntities(query,{ rows ->
            if (!entityAuthorized(source,runtime.permissions)) return@lookupEntities
            source.sendSystemMessage(Component.literal("Guardian entity history: ${rows.size} records (page ${filter.page ?: 1})."))
            rows.forEach { source.sendSystemMessage(com.bareminimumstudios.guardian.lookup.EntityHistoryFormatter.line(it)) }
            source.sendSystemMessage(com.bareminimumstudios.guardian.lookup.HistoryNavigation.footer(filter.page ?: 1,rows.size==limit,filter.oldestFirst,
                { page -> HistoryPageCommands.command("entities",navigationRaw,page,filter.oldestFirst) },HistoryPageCommands.command("entities",navigationRaw,1,!filter.oldestFirst)))
        },{ if(entityAuthorized(source,runtime.permissions)) source.sendFailure(Component.literal("Guardian entity lookup failed; see server log.")) })
        return 1
    }

    private fun executeActivity(source: CommandSourceStack, raw: String, name: String, runtime: GuardianRuntime?, config: GuardianConfig): Int {
        val history = runtime?.history() ?: run { source.sendFailure(Component.literal("Guardian history is unavailable.")); return 0 }
        val allowed = setOf("u", "user", "t", "time", "l", "limit", "p", "page", "o", "order")
        if (raw.split(Regex("\\s+")).any { it.isNotBlank() && it.substringBefore(':').lowercase() !in allowed }) {
            source.sendFailure(Component.literal("Guardian $name accepts user, time, limit, page and order filters only.")); return 0
        }
        val filter = BlockCommandFilterParser.parse(raw).getOrElse { source.sendFailure(Component.literal("Guardian $name: ${it.message}")); return 0 }
        val limit = filter.limit ?: config.lookup.defaultResults.get()
        if (limit > minOf(500, config.lookup.maxResults.get())) { source.sendFailure(Component.literal("Guardian activity page limit exceeded.")); return 0 }
        val kinds = when(name) {
            "chat" -> setOf(com.bareminimumstudios.guardian.domain.PlayerActivityKind.CHAT)
            "commands" -> setOf(com.bareminimumstudios.guardian.domain.PlayerActivityKind.COMMAND)
            else -> setOf(com.bareminimumstudios.guardian.domain.PlayerActivityKind.LOGIN, com.bareminimumstudios.guardian.domain.PlayerActivityKind.LOGOUT)
        }
        val query = com.bareminimumstudios.guardian.storage.query.PlayerActivityQuery(kinds, search = resolvedSearch(source, filter),
            afterEpochMillis = filter.lookbackMillis?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) }, limit = limit, offset = ((filter.page ?: 1) - 1) * limit, oldestFirst = filter.oldestFirst)
        history.lookupActivity(query, { rows ->
            if (!activityAuthorized(source, runtime.permissions, name)) return@lookupActivity
            source.sendSystemMessage(Component.literal("Guardian $name history: ${rows.size} records (page ${filter.page ?: 1})."))
            rows.forEach { row ->
                val details = when (row.kind) { com.bareminimumstudios.guardian.domain.PlayerActivityKind.LOGIN -> "logged in"; com.bareminimumstudios.guardian.domain.PlayerActivityKind.LOGOUT -> "logged out"; com.bareminimumstudios.guardian.domain.PlayerActivityKind.COMMAND -> "issued ${row.text}"; else -> "said: ${row.text}" }
                val plain = "${java.time.Instant.ofEpochMilli(row.timestampEpochMillis)} ${row.actor.lastKnownName} $details".filter { !it.isISOControl() }.replace('§', '?')
                source.sendSystemMessage(Component.literal(plain))
            }
            source.sendSystemMessage(com.bareminimumstudios.guardian.lookup.HistoryNavigation.footer(filter.page ?: 1, rows.size == limit, filter.oldestFirst, { page -> HistoryPageCommands.command(name, raw, page, filter.oldestFirst) }, HistoryPageCommands.command(name, raw, 1, !filter.oldestFirst)))
        }, { if (activityAuthorized(source, runtime.permissions, name)) source.sendFailure(Component.literal("Guardian $name lookup failed; see server log.")) })
        return 1
    }

    private fun executeTransactions(source: CommandSourceStack, raw: String, runtime: GuardianRuntime?, config: GuardianConfig): Int {
        val history = runtime?.history() ?: run { source.sendFailure(Component.literal("Guardian history is unavailable.")); return 0 }
        val filter = BlockCommandFilterParser.parse(raw).getOrElse { source.sendFailure(Component.literal("Guardian transactions: ${it.message}")); return 0 }
        if (filter.actions.isNotEmpty() || filter.causes.isNotEmpty()) { source.sendFailure(Component.literal("Item history does not accept block action or cause filters.")); return 0 }
        val scope = resolveScope(source, filter) ?: return 0
        val limit = filter.limit ?: config.lookup.defaultResults.get()
        if (limit > minOf(500, config.lookup.maxResults.get()) || (filter.radius ?: 0) > config.lookup.maxRadius.get()) {
            source.sendFailure(Component.literal("Guardian transaction limit/radius exceeds the configured maximum (limit at most 500).")); return 0
        }
        val query = com.bareminimumstudios.guardian.storage.query.ContainerLookupQuery(
            dimension = if (filter.explicitPosition != null || filter.radius != null || scope.bounds != null) scope.dimension else null,
            position = filter.explicitPosition ?: filter.radius?.let { sourcePosition(source) },
            search = resolvedSearch(source, filter), limit = limit,
            afterEpochMillis = filter.lookbackMillis?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) },
            radius = filter.radius, bounds = scope.bounds, offset = ((filter.page ?: 1) - 1) * limit, oldestFirst = filter.oldestFirst
        )
        val navigationRaw = if (filter.radius != null && filter.explicitPosition == null) "$raw x:${query.position!!.x} y:${query.position!!.y} z:${query.position!!.z}" else raw
        history.lookupContainers(query, { rows ->
            if (!authorized(source, runtime.permissions, LOOKUP_PERMISSION, 2)) return@lookupContainers
            com.bareminimumstudios.guardian.lookup.ContainerHistoryFormatter.lines(rows).forEach(source::sendSystemMessage)
            source.sendSystemMessage(com.bareminimumstudios.guardian.lookup.HistoryNavigation.footer(filter.page ?: 1, rows.size == limit, filter.oldestFirst,
                { page -> HistoryPageCommands.command("transactions", navigationRaw, page, filter.oldestFirst) }, HistoryPageCommands.command("transactions", navigationRaw, 1, !filter.oldestFirst)))
        }, { if (authorized(source, runtime.permissions, LOOKUP_PERMISSION, 2)) source.sendFailure(Component.literal("Guardian item lookup failed; see server log.")) })
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
        .then(Commands.literal("page").then(Commands.argument("page", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 10000)).executes { context ->
            val player = context.source.player ?: return@executes 0
            BlockInspector.showPage(player, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "page")); 1
        }))
        .then(Commands.literal("order").then(Commands.literal("oldest").executes { context -> context.source.player?.let { BlockInspector.setOrder(it, true) }; 1 })
            .then(Commands.literal("newest").executes { context -> context.source.player?.let { BlockInspector.setOrder(it, false) }; 1 }))
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
        configProvider: () -> GuardianConfig,
        operation: BlockHistoryOperation = BlockHistoryOperation.ROLLBACK
    ) = Commands.literal(name)
        .requires { permissions.has(it, operation.permission, 3) }
        .executes { context ->
            context.source.sendSystemMessage(
                Component.literal("Usage: /guardian ${operation.command} t:<time> [u:<player>] [r:<radius|#worldedit>] [a:<place|break|change>] [x:<x> y:<y> z:<z>]")
            )
            0
        }
        .then(Commands.literal("preview")
            .executes { context -> context.source.sendSystemMessage(Component.literal("Usage: /guardian ${operation.command} preview t:1h r:5. Read-only; no blocks change.")); 0 }
            .then(Commands.argument("filters", StringArgumentType.greedyString())
                .suggests { context, builder -> suggestFilters(context.source, builder, permissions, permission = operation.permission, fallback = 3, extended = false) }
                .executes { context -> executeRollback(context.source, StringArgumentType.getString(context, "filters"), runtimeProvider(), configProvider(), previewOnly = true, operation = operation) }))
        .then(
            Commands.argument("params", StringArgumentType.greedyString())
                .suggests { context, builder -> suggestFilters(context.source, builder, permissions, permission = operation.permission, fallback = 3, extended = false) }
                .executes { context ->
                    executeRollback(
                        source = context.source,
                        raw = StringArgumentType.getString(context, "params"),
                        runtime = runtimeProvider(),
                        config = configProvider(),
                        operation = operation
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
                    "Guardian ${h.backendId} schema=${h.schemaVersion} itemRecovery=${h.unfinishedItemRollbacks} | writer=${p.state} queued=${p.queued} " +
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
            search = resolvedSearch(source, filter),
            actions = filter.actions, causes = filter.causes,
            afterEpochMillis = filter.lookbackMillis?.let { (now - it).coerceAtLeast(0L) },
            limit = requestedLimit, offset = ((filter.page ?: 1) - 1) * requestedLimit, oldestFirst = filter.oldestFirst
        )

        val navigationRaw = if (radius != null && filter.explicitPosition == null) "$raw x:${position!!.x} y:${position.y} z:${position.z}" else raw
        source.sendSystemMessage(Component.literal("Guardian: searching block history..."))
        history.lookup(
            query,
            onSuccess = { rows ->
                if (!authorized(source, runtime.permissions, LOOKUP_PERMISSION, 2)) return@lookup
                BlockHistoryFormatter.lines(rows).forEach(source::sendSystemMessage)
                source.sendSystemMessage(com.bareminimumstudios.guardian.lookup.HistoryNavigation.footer(filter.page ?: 1, rows.size == requestedLimit, filter.oldestFirst,
                    { page -> HistoryPageCommands.command("lookup", navigationRaw, page, filter.oldestFirst) }, HistoryPageCommands.command("lookup", navigationRaw, 1, !filter.oldestFirst)))
            },
            onFailure = { if (authorized(source, runtime.permissions, LOOKUP_PERMISSION, 2)) source.sendFailure(Component.literal("Guardian lookup failed; see the server log.")) }
        )
        return 1
    }

    private fun executeRollback(
        source: CommandSourceStack,
        raw: String,
        runtime: GuardianRuntime?,
        config: GuardianConfig,
        previewOnly: Boolean = false,
        operation: BlockHistoryOperation = BlockHistoryOperation.ROLLBACK
    ): Int {
        val rollback = runtime?.rollback()
        if (rollback == null) {
            source.sendFailure(Component.literal("Guardian ${operation.command} service is not running."))
            return 0
        }

        val filter = BlockCommandFilterParser.parse(raw).getOrElse {
            source.sendFailure(Component.literal("Guardian ${operation.command}: ${it.message}"))
            return 0
        }
        if (filter.search.extended) {
            source.sendFailure(Component.literal("Extended user and resource filters are history-only. Block ${operation.command} still accepts one included user.")); return 0
        }
        val lookbackMillis = filter.lookbackMillis ?: run {
            source.sendFailure(Component.literal("Guardian ${operation.command} requires a t:<time> filter for safety."))
            return 0
        }
        if (filter.limit != null || filter.page != null) {
            source.sendFailure(Component.literal("Guardian ${operation.command} does not accept l: or p:. Narrow the ${operation.command} with t:, r:, or u: instead."))
            return 0
        }

        val scope = resolveScope(source, filter) ?: return 0
        val radius = if (scope.bounds == null) (filter.radius ?: config.rollback.defaultRadius.get()) else null
        if (radius != null && radius > config.rollback.maxRadius.get()) {
            source.sendFailure(Component.literal("Guardian ${operation.command} radius exceeds the configured maximum of ${config.rollback.maxRadius.get()}."))
            return 0
        }
        val center = if (scope.bounds == null) (filter.explicitPosition ?: sourcePosition(source)) else null
        val now = System.currentTimeMillis()
        if(filter.causes.any { it==com.bareminimumstudios.guardian.domain.ChangeCause.FIRE || it==com.bareminimumstudios.guardian.domain.ChangeCause.EXPLOSION }) {
            source.sendFailure(Component.literal("Guardian fire/explosion history is read-only in this checkpoint; rollback/restore is unavailable for those causes."));return 0
        }
        if(filter.causes.any { it==com.bareminimumstudios.guardian.domain.ChangeCause.GROWTH || it==com.bareminimumstudios.guardian.domain.ChangeCause.DECAY }) {
            source.sendFailure(Component.literal("Guardian growth/decay history is read-only in this checkpoint; rollback/restore is unavailable for those causes."));return 0
        }
        val query = BlockLookupQuery(
            dimension = scope.dimension,
            position = center,
            bounds = scope.bounds,
            radius = radius,
            actorUuid = filter.actorName?.let { source.server.playerList.players.firstOrNull { player -> player.gameProfile.name.equals(it, ignoreCase = true) }?.uuid },
            actorName = filter.actorName?.takeIf { source.server.playerList.players.none { player -> player.gameProfile.name.equals(it, ignoreCase = true) } },
            actions = filter.actions,
            causes = (filter.causes.ifEmpty { com.bareminimumstudios.guardian.domain.ChangeCause.entries.toSet() }) - setOf(com.bareminimumstudios.guardian.domain.ChangeCause.FIRE,com.bareminimumstudios.guardian.domain.ChangeCause.EXPLOSION,com.bareminimumstudios.guardian.domain.ChangeCause.GROWTH,com.bareminimumstudios.guardian.domain.ChangeCause.DECAY),
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
        return if (when {
            operation == BlockHistoryOperation.RESTORE && previewOnly -> rollback.previewRestore(source, query, description)
            operation == BlockHistoryOperation.RESTORE -> rollback.restore(source, query, description)
            previewOnly -> rollback.preview(source, query, description)
            else -> rollback.request(source, query, description)
        }) 1 else 0
    }

    private fun executeRecovery(source: CommandSourceStack,raw: String?,runtime: GuardianRuntime?,saved: Boolean=false): Int {
        val recovery=runtime?.itemRecovery()
        if(recovery==null) { source.sendFailure(Component.literal("Guardian item recovery view is unavailable."));return 0 }
        val id=if(raw==null) null else runCatching { java.util.UUID.fromString(raw).also { require(it.toString().equals(raw,ignoreCase=true)) } }.getOrElse { source.sendFailure(Component.literal("Guardian: use a complete operation UUID from /guardian rollback-items recovery."));return 0 }
        return if(recovery.request(source,id,saved)) 1 else 0
    }

    private fun executeItemPreview(source: CommandSourceStack,raw: String,runtime: GuardianRuntime?,config: GuardianConfig): Int {
        val preview=runtime?.itemRollbackPreview()
        if (preview == null) { source.sendFailure(Component.literal("Guardian item rollback preview is unavailable."));return 0 }
        val filter=ItemRollbackPreviewFilters.parse(raw).getOrElse { source.sendFailure(Component.literal("Guardian item rollback preview: ${it.message}"));return 0 }
        val scope=resolveScope(source,filter) ?: return 0
        val radius=if (scope.bounds == null) filter.radius ?: config.rollback.defaultRadius.get() else null
        if (radius != null && radius > config.rollback.maxRadius.get()) { source.sendFailure(Component.literal("Item preview radius exceeds the configured rollback maximum."));return 0 }
        val center=if (scope.bounds == null) filter.explicitPosition ?: sourcePosition(source) else null
        val player=filter.actorName?.let { name -> source.server.playerList.players.firstOrNull { it.gameProfile.name.equals(name,ignoreCase=true) } }
        val query=com.bareminimumstudios.guardian.storage.query.ContainerLookupQuery(dimension=scope.dimension,position=center,bounds=scope.bounds,radius=radius,actorUuid=player?.uuid,actorName=if(player==null)filter.actorName else null,afterEpochMillis=(System.currentTimeMillis()-checkNotNull(filter.lookbackMillis)).coerceAtLeast(0),limit=51)
        return if(preview.request(source,query,config.rollback.maxRecords.get().coerceAtMost(50))) 1 else 0
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
