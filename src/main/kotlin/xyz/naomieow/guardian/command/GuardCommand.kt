package xyz.naomieow.guardian.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.StdOutSqlLogger
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import xyz.naomieow.guardian.Guardian
import xyz.naomieow.guardian.config.ActionConfig
import xyz.naomieow.guardian.config.DatabaseConfig
import xyz.naomieow.guardian.database.table.BlockStateModification
import xyz.naomieow.guardian.ext.inspectMode

object GuardCommand {
    val command: LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("guard")
        .requires(CommandPermissions.root_command::check)
        .then(Commands.literal("reload")
            .executes(::reloadConfig)
        )
        .then(Commands.literal("lookup")
            .requires(CommandPermissions.lookup_mode::check)
            .then(Commands.argument("radius", IntegerArgumentType.integer())
                .executes(::lookupRadius)
            )
        )
        .then(Commands.literal("inspect")
            .requires(CommandPermissions.inspect_mode::check)
            .executes(::inspectMode)
        )

    private fun inspectMode(ctx: CommandContext<CommandSourceStack>): Int {
        if (ctx.source.player == null) {
            ctx.source.sendSystemMessage(Component.literal("Attempted to call command from non-player environment"))
            return 0
        }
        ctx.source.player!!.inspectMode = !ctx.source.player!!.inspectMode
        if (ctx.source.player!!.inspectMode) {
            ctx.source.sendSystemMessage(Component.literal("Entered inspect mode."))
        } else {
            ctx.source.sendSystemMessage(Component.literal("Exited inspect mode."))
        }
        return 1
    }

    private fun reloadConfig(ctx: CommandContext<CommandSourceStack>): Int {
        Guardian.databaseConfig = DatabaseConfig.load()
        Guardian.actionConfig = ActionConfig.load()
        ctx.source.sendSystemMessage(Component.literal("Reloaded config!"))
        // TODO: Diff message of changed config values.
        return 1
    }

    private fun lookupRadius(ctx: CommandContext<CommandSourceStack>): Int {
        val radius = IntegerArgumentType.getInteger(ctx, "radius")
        if (ctx.source.player == null) {
            ctx.source.sendSystemMessage(
                Component.literal("Attempted to call command from non-player environment")
            )
            return 0
        }
        val center = ctx.source.player!!.blockPosition()
        val actions = transaction {
            SchemaUtils.create(BlockStateModification)
            addLogger(StdOutSqlLogger)

            BlockStateModification
                .selectAll()
                .orderBy(BlockStateModification.performedAt to SortOrder.ASC)
                .filter { row ->
                    center.distToCenterSqr(Vec3(
                        row[BlockStateModification.posX].toDouble(),
                        row[BlockStateModification.posY].toDouble(),
                        row[BlockStateModification.posZ].toDouble(),
                    )) <= radius * radius
                }
                .forEach { row ->
                    ctx.source.sendSystemMessage(Component.literal(
                        "${
                            row[BlockStateModification.performedAt].date
                        } ${
                            row[BlockStateModification.performedAt].time
                        } | ${
                            row[BlockStateModification.playerName]
                        }: [${row[BlockStateModification.posX]}, ${
                            row[BlockStateModification.posY]
                        }, ${row[BlockStateModification.posZ]}], ${
                            row[BlockStateModification.oldState].block.name.string
                        } -> ${
                            row[BlockStateModification.newState].block.name.string
                        }"
                    ))
                }
        }
        return 1
    }

    object CommandPermissions {
        val root_command: Permission = Permission("guardian.command", 2)
        val inspect_mode: Permission = Permission("guardian.command.inspect", 2)
        val lookup_mode: Permission = Permission("guardian.command.lookup", 2)
    }
}