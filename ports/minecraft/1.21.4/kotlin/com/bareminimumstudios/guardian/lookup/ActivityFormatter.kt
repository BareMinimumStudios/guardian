package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.domain.*
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent

object ActivityFormatter {
    fun console(row:PlayerActivity):Component {
        val target=if(row.kind==PlayerActivityKind.WHISPER)" → "+row.recipientName else ""
        val text=(row.text?.let { ": "+it } ?: "").filter { !it.isISOControl() && it.code!=167 }
        return Component.literal("${java.time.Instant.ofEpochMilli(row.timestampEpochMillis)} [${row.kind}] ${row.actor.lastKnownName}$target$text")
    }
    fun line(row:PlayerActivity,now:Long=System.currentTimeMillis(),spy:Boolean=false,needle:String?=null):Component {
        val color=when(row.kind) { PlayerActivityKind.WHISPER->ChatFormatting.LIGHT_PURPLE;PlayerActivityKind.COMMAND->ChatFormatting.GOLD;PlayerActivityKind.LOGIN->ChatFormatting.GREEN;PlayerActivityKind.LOGOUT->ChatFormatting.GRAY;else->ChatFormatting.AQUA }
        val label=when(row.kind) { PlayerActivityKind.WHISPER->"Whisper";PlayerActivityKind.COMMAND->"Command";PlayerActivityKind.LOGIN->"Join";PlayerActivityKind.LOGOUT->"Leave";else->"Chat" }
        val seconds=((now-row.timestampEpochMillis).coerceAtLeast(0)/1000)
        val age=when { seconds<60->"${seconds}s ago";seconds<3600->"${seconds/60}m ago";seconds<86400->"${seconds/3600}h ago";else->"${seconds/86400}d ago" }
        val result=Component.literal(if(spy)"[Guardian spy] " else "$age · ").withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal("[$label] ").withStyle(color))
            .append(Component.literal(row.actor.lastKnownName ?: row.actor.uuid.toString()).withStyle(ChatFormatting.YELLOW))
        if(row.kind==PlayerActivityKind.WHISPER)result.append(Component.literal(" → ").withStyle(ChatFormatting.GRAY)).append(Component.literal(row.recipientName.orEmpty()).withStyle(ChatFormatting.YELLOW))
        if(row.text!=null) {
            result.append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
            val text=row.text.orEmpty().filter { !it.isISOControl() && it.code!=167 }
            val end=text.offsetByCodePoints(0,minOf(180,text.codePointCount(0,text.length)))
            val preview=text.substring(0,end);var offset=0
            while(offset<preview.length) {
                val at=if(needle==null)-1 else preview.indexOf(needle,offset,true)
                if(at<0) { result.append(Component.literal(preview.substring(offset)).withStyle(ChatFormatting.WHITE));break }
                result.append(Component.literal(preview.substring(offset,at)).withStyle(ChatFormatting.WHITE))
                result.append(Component.literal(preview.substring(at,at+needle!!.length)).withStyle(ChatFormatting.GOLD,ChatFormatting.BOLD));offset=at+needle.length
            }
            if(end<text.length)result.append(Component.literal("… [hover for full text]").withStyle(ChatFormatting.GRAY))
        }
        val full="${java.time.Instant.ofEpochMilli(row.timestampEpochMillis)}\nEvent: ${row.eventId}\nSender: ${row.actor.uuid}"+
            (row.recipientUuid?.let { "\nRecipient: $it" } ?: "")+"\n"+row.text.orEmpty().filter { !it.isISOControl() && it.code!=167 }
        return result.withStyle { it.withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT,Component.literal(full))) }
    }
}
