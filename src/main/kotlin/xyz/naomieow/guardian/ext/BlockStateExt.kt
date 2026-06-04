package xyz.naomieow.guardian.ext

import com.google.gson.JsonElement
import com.mojang.serialization.JsonOps
import net.minecraft.world.level.block.state.BlockState
import kotlin.jvm.optionals.getOrNull

fun BlockState.toJson(): JsonElement? {
    val res = BlockState.CODEC.encodeStart(JsonOps.INSTANCE, this)
    return res.result().getOrNull()
}

// I yearn for kotlin 2.5
// https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0449-companions-block-extension.md
fun blockStateFromJson(json: JsonElement): BlockState? {
    val res = BlockState.CODEC.parse(JsonOps.INSTANCE, json)
    return res.result().getOrNull()
}