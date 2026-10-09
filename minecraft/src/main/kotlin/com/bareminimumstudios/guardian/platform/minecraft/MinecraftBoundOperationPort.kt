package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.ItemOperationPort
import com.bareminimumstudios.guardian.rollback.ItemOwnerCoordination
import net.minecraft.server.MinecraftServer
import java.util.concurrent.CompletionStage

/** Internal adapter. Binding is not exclusion; the caller must supply a trusted exclusion contract. */
internal class MinecraftBoundOperationPort(
    private val server: MinecraftServer,
    private val lease: ItemOwnerCoordination.Lease,
    private val session: MinecraftBoundInventories,
    private val exclusive: () -> Boolean
) : ItemOperationPort {
    private var closed = false
    override fun isExclusiveAndCurrent(owners: Set<ItemSlotOwner>): Boolean {
        check(server.isSameThread)
        return !closed && owners == lease.owners && lease.isCurrent(owners) && exclusive() && session.isCurrent()
    }
    override fun readOwners(owners: Set<ItemSlotOwner>): InventorySnapshot? =
        if (isExclusiveAndCurrent(owners)) session.read() else null
    override fun writeSlot(address: ItemSlotAddress, expected: ItemStackSnapshot, replacement: ItemStackSnapshot) {
        check(isExclusiveAndCurrent(lease.owners))
        session.write(address, expected, replacement)
        check(isExclusiveAndCurrent(lease.owners))
    }
    override fun saveAndReadBack(owner: ItemSlotOwner, addresses: Set<ItemSlotAddress>): CompletionStage<InventorySnapshot?> {
        check(isExclusiveAndCurrent(lease.owners) && owner in lease.owners && addresses.all { it.owner == owner })
        return session.saveAndReadBack(owner).thenApply<InventorySnapshot?> { it }
    }
    override fun close() {
        check(server.isSameThread)
        if (closed) return
        closed = true
        session.close()
    }
}
