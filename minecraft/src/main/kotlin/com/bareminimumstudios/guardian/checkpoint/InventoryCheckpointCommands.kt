package com.bareminimumstudios.guardian.checkpoint

import com.mojang.brigadier.suggestion.Suggestions
import com.bareminimumstudios.guardian.GuardianRuntime
import com.bareminimumstudios.guardian.command.CheckpointCommandFilters
import com.bareminimumstudios.guardian.permission.PermissionService
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import java.util.UUID

/** Separate backup authority; these commands never fall back to transfer history or radius queries. */
internal object InventoryCheckpointCommands {
    fun node(permissions:PermissionService,runtime:()->GuardianRuntime?) = Commands.literal("inventory")
        .requires { permissions.has(it,"guardian.inventory",3) }
        .executes { context -> result(runtime()?.checkpoints()?.list(context.source,"")) }
        .then(Commands.literal("list")
            .executes { context -> result(runtime()?.checkpoints()?.list(context.source,"")) }
            .then(Commands.argument("filters",StringArgumentType.greedyString())
                .suggests { context,builder ->
                    val start=builder.input.lastIndexOf(' ').plus(1).coerceAtLeast(builder.start)
                    val choices=if(runtime()?.checkpoints()?.permitted(context.source)==true)
                        CheckpointCommandFilters.suggestions(builder.input.substring(start),context.source.server.playerList.players.map { it.gameProfile.name }) else emptyList()
                    SharedSuggestionProvider.suggest(choices,builder.createOffset(start))
                }
                .executes { context -> result(runtime()?.checkpoints()?.list(context.source,StringArgumentType.getString(context,"filters"))) }))
        .then(Commands.literal("snapshot").requires { permissions.has(it,"guardian.inventory.snapshot",3) }
            .then(Commands.argument("player",StringArgumentType.greedyString())
                .suggests { context,builder ->
                    val choices=if(runtime()?.checkpoints()?.permitted(context.source)==true)
                        context.source.server.playerList.players.map { "u:${it.gameProfile.name}" } else emptyList()
                    SharedSuggestionProvider.suggest(choices,builder)
                }
                .executes { context -> result(runtime()?.checkpoints()?.capture(context.source,StringArgumentType.getString(context,"player"))) }))
        .then(Commands.literal("view").then(Commands.argument("checkpoint",StringArgumentType.word())
            .suggests { context,builder -> runtime()?.checkpoints()?.suggestIds(context.source,builder) ?: Suggestions.empty() }
            .executes { context -> id(context.source,StringArgumentType.getString(context,"checkpoint")) { result(runtime()?.checkpoints()?.view(context.source,it)) } }))
        .then(Commands.literal("restore").requires { permissions.has(it,"guardian.inventory.restore",4) }
            .executes { context -> context.source.sendSystemMessage(Component.literal("Use /guardian inventory restore preview <checkpoint>, apply <checkpoint>, confirm <token>, reconcile, or cancel. Full snapshot replacement can recreate items moved elsewhere."));0 }
            .then(Commands.literal("preview").then(Commands.argument("checkpoint",StringArgumentType.word())
                .suggests { context,builder -> runtime()?.checkpoints()?.suggestIds(context.source,builder) ?: Suggestions.empty() }
                .executes { context -> id(context.source,StringArgumentType.getString(context,"checkpoint")) { result(runtime()?.checkpoints()?.preview(context.source,it)) } }))
            .then(Commands.literal("apply").then(Commands.argument("checkpoint",StringArgumentType.word())
                .suggests { context,builder -> runtime()?.checkpoints()?.suggestIds(context.source,builder) ?: Suggestions.empty() }
                .executes { context -> id(context.source,StringArgumentType.getString(context,"checkpoint")) { value ->
                    val current=runtime();val service=current?.checkpoints()
                    if(service?.permitted(context.source,true)!=true)0 else result(current.onlineItems()?.requestCheckpointRestore(context.source,value,service.limits))
                } }))
            .then(Commands.literal("confirm").then(Commands.argument("token",StringArgumentType.word())
                .suggests { context,builder -> SharedSuggestionProvider.suggest(runtime()?.onlineItems()?.confirmationSuggestions(context.source) ?: emptyList(),builder) }
                .executes { context -> result(runtime()?.onlineItems()?.confirmCheckpointRestore(context.source,StringArgumentType.getString(context,"token"))) }))
            .then(Commands.literal("reconcile").executes { context -> if(runtime()?.checkpoints()?.permitted(context.source,true)==true)result(runtime()?.onlineItems()?.reconcile(context.source)) else 0 })
            .then(Commands.literal("cancel").executes { context -> if(runtime()?.checkpoints()?.permitted(context.source,true)==true)result(runtime()?.onlineItems()?.cancel(context.source)) else 0 }))

    private fun result(value:Boolean?)=if(value==true)1 else 0
    private fun id(source:CommandSourceStack,value:String,run:(UUID)->Int):Int {
        val id=runCatching { UUID.fromString(value).also { require(it.toString().equals(value,true)) } }.getOrNull()
        if(id==null) { source.sendFailure(Component.literal("Use a full checkpoint UUID from /guardian inventory list."));return 0 }
        return run(id)
    }
}
