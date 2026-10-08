package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.ItemSlotOwner

class ItemTransferCoordination(private val owners: ItemOwnerCoordination) {
    fun allowsExternalTransfer(resolve: () -> Collection<ItemSlotOwner>?): Boolean {
        if (!owners.isRunning()) return false
        if (!owners.hasReservations()) return true
        val endpoints = try { resolve()?.toSet() } catch (_: Exception) { null } ?: return false
        if (endpoints.isEmpty() || endpoints.size > 4 || endpoints.any { it !is ItemSlotOwner.BlockContainer }) return false
        return endpoints.all { owners.allowsMutation(it) }
    }
}
