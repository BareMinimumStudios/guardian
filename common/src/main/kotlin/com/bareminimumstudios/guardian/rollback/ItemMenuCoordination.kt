package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.ItemSlotOwner
import java.util.UUID

/** Policy only. Platform hooks must reject packets before client prediction is accepted. */
class ItemMenuCoordination(private val owners: ItemOwnerCoordination) {
    fun allowsMutation(playerId: UUID, resolve: () -> Collection<ItemSlotOwner>?): Boolean {
        if (!owners.isRunning()) return false
        if (!owners.hasReservations()) return true
        if (!owners.allowsMutation(ItemSlotOwner.PlayerInventory(playerId))) return false
        val participating = resolveOwners(resolve) ?: return false
        return participating.all { owners.allowsMutation(it) }
    }

    /** Cleanup must run; revoke affected operations before vanilla returns carried items. */
    fun beforeCleanup(playerId: UUID, resolve: () -> Collection<ItemSlotOwner>?) {
        if (!owners.isRunning() || !owners.hasReservations()) return
        owners.invalidate(ItemSlotOwner.PlayerInventory(playerId))
        if (!owners.hasReservations()) return
        val participating = resolveOwners(resolve)
        if (participating == null) owners.invalidateAll()
        else participating.forEach(owners::invalidate)
    }

    private fun resolveOwners(resolve: () -> Collection<ItemSlotOwner>?): Set<ItemSlotOwner>? = try {
        val result = resolve()
        if (result == null || result.size > 32 || result.any {
            it !is ItemSlotOwner.BlockContainer && it !is ItemSlotOwner.PlayerInventory
        }) null else result.toSet()
    } catch (_: Exception) { null }
}
