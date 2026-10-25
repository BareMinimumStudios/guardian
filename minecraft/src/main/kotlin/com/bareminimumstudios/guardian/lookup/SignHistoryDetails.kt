package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.domain.BlockStateSnapshot
import com.bareminimumstudios.guardian.domain.BlockChangeSnapshot
import com.bareminimumstudios.guardian.platform.minecraft.MinecraftNbtPayloadCodec
import net.minecraft.nbt.NbtOps
import net.minecraft.world.level.block.entity.SignText

/** Render plain literals only; stored component styles and click actions never become chat actions. */
object SignHistoryDetails {
    private fun text(state:BlockStateSnapshot,front:Boolean):SignText {
        val tag=MinecraftNbtPayloadCodec.decode(requireNotNull(state.blockEntityData))
        return SignText.DIRECT_CODEC.parse(NbtOps.INSTANCE,tag.getCompound(if(front)"front_text" else "back_text")).getOrThrow()
    }
    private fun lines(text:SignText,filtered:Boolean)=(0..3).map { text.getMessage(it,filtered).string }
    fun sides(row:BlockChangeSnapshot):List<Boolean> = runCatching { listOf(true,false).filter { side->
        val before=text(row.before,side);val after=text(row.after,side)
        lines(before,false)!=lines(after,false) || lines(before,true)!=lines(after,true)
    } }.getOrDefault(emptyList())
    fun summary(row:BlockChangeSnapshot):String = when(sides(row)) { listOf(true)->"edited front sign text";listOf(false)->"edited back sign text";listOf(true,false)->"edited front/back sign text";else->"edited sign text (details unavailable)" }
    fun hover(row:BlockChangeSnapshot):String = runCatching {
        sides(row).joinToString("\n") { side->
            fun render(state:BlockStateSnapshot)=lines(text(state,side),true).mapIndexed { i,line->"${i+1}: "+line.filter { !it.isISOControl() && it.code!=167 }.take(256) }.joinToString("\n")
            "${if(side)"Front" else "Back"} sign text (filtered view)\nBefore:\n${render(row.before)}\nAfter:\n${render(row.after)}"
        }.ifEmpty { "Sign text details unavailable" }
    }.getOrDefault("Sign text details unavailable")
}
