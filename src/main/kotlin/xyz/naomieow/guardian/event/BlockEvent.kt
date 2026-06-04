package xyz.naomieow.guardian.event

import invoke.kitty.nullevt.Event
import invoke.kitty.nullevt.api.CancellationStrategy
import invoke.kitty.nullevt.newCustomEvent
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

object BlockEvent {
    object Break {
        var BEFORE: Event<Before> = newCustomEvent(false, CancellationStrategy.ForTrue)
        var AFTER: Event<After> = newCustomEvent(false, CancellationStrategy.ForTrue)
        var CANCELLED: Event<Cancelled> = newCustomEvent(false, CancellationStrategy.ForTrue)

        fun interface Before {
            fun invoke(
                level: Level,
                player: Player?,
                pos: BlockPos,
                state: BlockState,
                blockEntity: BlockEntity?,
            ): Boolean
        }

        fun interface After {
            fun invoke(
                level: Level,
                player: Player?,
                pos: BlockPos,
                state: BlockState,
                blockEntity: BlockEntity?,
            ): Boolean
        }

        fun interface Cancelled {
            fun invoke(
                level: Level,
                player: Player?,
                pos: BlockPos,
                state: BlockState,
                blockEntity: BlockEntity?,
            ): Boolean
        }
    }
}


