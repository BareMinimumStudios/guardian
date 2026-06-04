package xyz.naomieow.guardian.command

import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import xyz.naomieow.guardian.Guardian
import xyz.naomieow.guardian.config.ActionConfig
import xyz.naomieow.guardian.config.DatabaseConfig

object GuardCommand {
    val command = Commands.literal("guard")
        .then(
            Commands.literal("reload")
                .executes(::reloadConfig)
        )

    private fun reloadConfig(ctx: CommandContext<CommandSourceStack>): Int {
        Guardian.databaseConfig = DatabaseConfig.load()
        Guardian.actionConfig = ActionConfig.load()
        ctx.source.sendSystemMessage(Component.literal("Reloaded config!"))
        // TODO: Diff message of changed config values.
        return 1
    }
}