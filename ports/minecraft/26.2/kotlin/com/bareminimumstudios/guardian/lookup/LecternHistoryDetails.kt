package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.domain.BlockChangeSnapshot
import com.bareminimumstudios.guardian.platform.minecraft.MinecraftNbtPayloadCodec

/** No book text, commands, names or component styles enter chat. */
object LecternHistoryDetails {
    fun summary(row:BlockChangeSnapshot):String = runCatching {
        val a=MinecraftNbtPayloadCodec.decode(requireNotNull(row.before.blockEntityData))
        val b=MinecraftNbtPayloadCodec.decode(requireNotNull(row.after.blockEntityData))
        when {
            !a.contains("Book") && b.contains("Book")->"inserted a lectern book"
            a.contains("Book") && !b.contains("Book")->"removed a lectern book"
            a.get("Book")!=b.get("Book")->"changed the lectern book"
            a.getInt("Page").orElseThrow()!=b.getInt("Page").orElseThrow()->"turned lectern page ${a.getInt("Page").orElseThrow()+1} -> ${b.getInt("Page").orElseThrow()+1}"
            else->"opened a lectern book"
        }
    }.getOrDefault("changed a lectern")
}
