package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.domain.BlockStateSnapshot
import com.bareminimumstudios.guardian.domain.BlockChangeSnapshot
import com.bareminimumstudios.guardian.platform.minecraft.MinecraftNbtPayloadCodec
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.world.level.block.entity.SignText

/** Stored components become bounded plain literals. Their actions/styles never become chat actions. */
object SignHistoryDetails {
    private data class Panel(val raw:List<String>,val filtered:List<String>,val color:String,val glow:Boolean,val antique:Boolean=false)
    private data class View(val waxed:Boolean,val panels:Map<String,Panel>)
    private fun read(state:BlockStateSnapshot):View {
        val tag=MinecraftNbtPayloadCodec.decode(requireNotNull(state.blockEntityData))
        fun vanilla(key:String):Panel {
            val text=SignText.DIRECT_CODEC.parse(NbtOps.INSTANCE,tag.getCompound(key)).getOrThrow()
            fun lines(filtered:Boolean)=(0..3).map { text.getMessage(it,filtered).string }
            return Panel(lines(false),lines(true),text.color.name.lowercase(),text.hasGlowingText())
        }
        fun custom(holder:CompoundTag):Panel {
            fun lines(key:String):List<String> {
                val result=ComponentSerialization.CODEC.listOf().parse(NbtOps.INSTANCE,requireNotNull(holder.get(key))).getOrThrow()
                require(result.size<=16)
                return result.map { it.string.also { line->require(line.length<=1024) } }
            }
            val raw=lines("message")
            return Panel(raw,if(holder.contains("filtered_message"))lines("filtered_message") else raw,holder.getString("color"),holder.getBoolean("has_glowing_text"),holder.getBoolean("has_antique_ink"))
        }
        if(state.blockId.toString().startsWith("supplementaries:")) {
            if(tag.contains("SignUp") && tag.contains("SignDown"))return View(tag.getBoolean("Waxed"),linkedMapOf("upper" to custom(tag.getCompound("SignUp").getCompound("TextHolder")),"lower" to custom(tag.getCompound("SignDown").getCompound("TextHolder"))))
            if(tag.contains("TextHolder"))return View(tag.getBoolean("Waxed"),mapOf("notice-board" to custom(tag.getCompound("TextHolder"))))
        }
        if(state.blockId.namespace=="suppsquared" && tag.contains("TextHolder"))return View(tag.getBoolean("Waxed"),mapOf("plaque" to custom(tag.getCompound("TextHolder"))))
        return View(tag.getBoolean("is_waxed"),linkedMapOf("front" to vanilla("front_text"),"back" to vanilla("back_text")))
    }
    fun changed(before:BlockStateSnapshot,after:BlockStateSnapshot):Boolean = read(before)!=read(after)
    fun sides(row:BlockChangeSnapshot):List<Boolean> = runCatching {
        val a=read(row.before);val b=read(row.after)
        listOf(true,false).filter { side->val key=if(side)"front" else "back";val x=a.panels[key];val y=b.panels[key];x!=null && y!=null && (x.raw!=y.raw || x.filtered!=y.filtered) }
    }.getOrDefault(emptyList())
    private fun changes(row:BlockChangeSnapshot):List<String> {
        val a=read(row.before);val b=read(row.after)
        require(a.panels.keys==b.panels.keys)
        val plaque=a.panels.keys==setOf("plaque")
        return buildList {
            if(a.waxed!=b.waxed)add(if(b.waxed) "waxed "+(if(plaque)"plaque" else "sign") else "removed "+(if(plaque)"plaque" else "sign")+" wax")
            val edited=mutableListOf<String>()
            for((side,x) in a.panels) {
                val y=b.panels.getValue(side)
                val label=if(side=="plaque")"plaque text" else "$side sign text"
                if(x.raw!=y.raw || x.filtered!=y.filtered)edited.add(side)
                if(x.color!=y.color)add("dyed $label ${plain(y.color)}")
                if(x.glow!=y.glow)add(if(y.glow)"made $label glow" else "removed $label glow")
                if(x.antique!=y.antique)add(if(y.antique)"applied antique ink to $label" else "removed antique ink from $label")
            }
            if(edited.isNotEmpty())add(if(plaque)"edited plaque text" else "edited ${edited.joinToString("/")} sign text")
        }
    }
    fun summary(row:BlockChangeSnapshot):String = runCatching { changes(row).joinToString("; ").ifEmpty { "changed sign (details unavailable)" } }.getOrDefault("edited sign text (details unavailable)")
    private fun plain(text:String)=text.filter { !it.isISOControl() && it.code!=167 }.take(256)
    fun hover(row:BlockChangeSnapshot):String = runCatching {
        val a=read(row.before);val b=read(row.after)
        buildList {
            addAll(changes(row))
            for((side,x) in a.panels) {
                val y=b.panels.getValue(side)
                if(x.raw==y.raw && x.filtered==y.filtered)continue
                fun render(panel:Panel)=panel.filtered.mapIndexed { i,line->"${i+1}: ${plain(line)}" }.joinToString("\n")
                add("${if(side=="plaque")"Plaque text" else side.replaceFirstChar { it.uppercase() }+" sign text"} (filtered view)\nBefore:\n${render(x)}\nAfter:\n${render(y)}")
            }
        }.joinToString("\n").ifEmpty { "Sign text details unavailable" }
    }.getOrDefault("Sign text details unavailable")
}
