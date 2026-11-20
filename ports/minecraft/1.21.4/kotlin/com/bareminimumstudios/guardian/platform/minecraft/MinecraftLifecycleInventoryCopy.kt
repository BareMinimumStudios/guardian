package com.bareminimumstudios.guardian.platform.minecraft

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack

/** Authorizes only the setters inside vanilla restoreFrom's exact inventory copy. */
internal class MinecraftLifecycleInventoryCopy(private val server: MinecraftServer) {
    private class Copy(val target: Inventory, val source: Inventory) {
        val stacks = List(41) { source.getItem(it) }
        val selected = source.selected
        var nextSlot = 0
        var writing = false
        var ticket: Int? = null
    }
    private var copy: Copy? = null

    fun restore(target: Inventory, source: Inventory, operation: Runnable) {
        check(server.isSameThread)
        check(copy == null) { "Nested lifecycle inventory copy" }
        val oldPlayer = source.player as? ServerPlayer
        val newPlayer = target.player as? ServerPlayer
        check(oldPlayer != null && newPlayer != null && oldPlayer !== newPlayer &&
            oldPlayer.server === server && newPlayer.server === server && oldPlayer.uuid == newPlayer.uuid &&
            source.javaClass == Inventory::class.java && target.javaClass == Inventory::class.java &&
            oldPlayer.inventory === source && newPlayer.inventory === target &&
            source.containerSize == 41 && target.containerSize == 41) { "Unsupported lifecycle inventory copy" }
        val current = Copy(target, source)
        copy = current
        try {
            operation.run()
            check(current.nextSlot == 41 && target.selected == current.selected &&
                current.stacks.indices.all { target.getItem(it) === current.stacks[it] && source.getItem(it) === current.stacks[it] }) {
                "Lifecycle inventory copy changed unexpectedly"
            }
        } finally {
            current.ticket = null
            copy = null
        }
    }

    fun setCopiedSlot(target: Inventory, slot: Int, stack: ItemStack, operation: Runnable) {
        check(server.isSameThread)
        val current = copy
        if (current == null) { operation.run(); return }
        check(!current.writing && current.target === target && slot == current.nextSlot &&
            slot in current.stacks.indices && stack === current.stacks[slot] && current.source.getItem(slot) === stack) {
            "Unexpected lifecycle inventory setter"
        }
        current.writing = true
        current.ticket = slot
        try {
            operation.run()
            check(current.ticket == null && target.getItem(slot) === stack) { "Lifecycle setter did not complete" }
            current.nextSlot++
        } finally {
            current.ticket = null
            current.writing = false
        }
    }

    fun consume(target: Inventory, slot: Int, stack: ItemStack): Boolean {
        check(server.isSameThread)
        val current = copy ?: return false
        if (!current.writing || current.target !== target || current.ticket != slot ||
            slot !in current.stacks.indices || stack !== current.stacks[slot]) return false
        // Consume before the setter body; callbacks cannot borrow the authorization.
        current.ticket = null
        return true
    }
}
