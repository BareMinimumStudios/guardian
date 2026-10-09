package com.bareminimumstudios.guardian.platform.minecraft

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents
import net.minecraft.world.item.enchantment.EnchantmentHelper
import java.util.function.Supplier

/** A one-use ticket for vanilla's vanishing-item removal, never an ambient death bypass. */
internal class MinecraftLifecycleItemRemoval(private val server: MinecraftServer) {
    private class Ticket(val inventory: Inventory, val slot: Int)
    private var ticket: Ticket? = null
    private var removing = false

    fun removeVanishing(inventory: Inventory, slot: Int, operation: Supplier<ItemStack>): ItemStack {
        check(server.isSameThread)
        check(!removing) { "Nested lifecycle item removal" }
        val player = inventory.player as? ServerPlayer
        check(player != null && player.server === server && player.inventory === inventory &&
            inventory.javaClass == Inventory::class.java && inventory.containerSize == 41 && slot in 0 until 41)
        val expected = inventory.getItem(slot)
        check(!expected.isEmpty && EnchantmentHelper.has(expected, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) {
            "Lifecycle removal requires an actual vanishing item"
        }
        removing = true
        ticket = Ticket(inventory, slot)
        try {
            val removed = operation.get()
            check(ticket == null && removed === expected && inventory.getItem(slot).isEmpty) { "Vanishing removal did not complete" }
            return removed
        } finally {
            ticket = null
            removing = false
        }
    }

    fun consume(inventory: Inventory, slot: Int): Boolean {
        check(server.isSameThread)
        val current = ticket ?: return false
        if (current.inventory !== inventory || current.slot != slot) return false
        ticket = null
        return true
    }
}
