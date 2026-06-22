package xyz.naomieow.guardian.command

import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.commands.CommandSourceStack
import net.minecraft.world.entity.player.Player

class Permission(val id: String, val fallback: Int) {
    fun check(player: Player): Boolean {
        return Permissions.check(player, id, fallback)
    }

    fun check(ctx: CommandSourceStack): Boolean {
        return Permissions.check(ctx, id, fallback)
    }
}