package xyz.naomieow.guardian.ext

import net.minecraft.server.level.ServerPlayer
import java.util.UUID

private val inspectDictionary: HashMap<UUID, Boolean> = HashMap()

var ServerPlayer.inspectMode: Boolean
    get() = inspectDictionary[this.uuid] ?: false
    set(value) = inspectDictionary.set(this.uuid, value)