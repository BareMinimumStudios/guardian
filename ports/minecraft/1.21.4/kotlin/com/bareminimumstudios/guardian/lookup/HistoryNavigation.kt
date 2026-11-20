package com.bareminimumstudios.guardian.lookup

import net.minecraft.network.chat.*
import net.minecraft.ChatFormatting

object HistoryNavigation {
    fun footer(page: Int, hasNext: Boolean, oldestFirst: Boolean, pageCommand: (Int) -> String, orderCommand: String): Component {
        val line = Component.empty()
        if (page > 1) line.append(button("[Previous]", pageCommand(page - 1)))
        line.append(Component.literal("  Page $page  ").withStyle(ChatFormatting.GRAY))
        if (hasNext && page < 10000) line.append(button("[Next]", pageCommand(page + 1)))
        line.append(Component.literal("  ")).append(button(if (oldestFirst) "[Oldest first]" else "[Newest first]", orderCommand))
        return line
    }
    private fun button(label: String, command: String) = Component.literal(label).withStyle {
        it.withColor(ChatFormatting.AQUA).withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
            .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command)))
    }
}
