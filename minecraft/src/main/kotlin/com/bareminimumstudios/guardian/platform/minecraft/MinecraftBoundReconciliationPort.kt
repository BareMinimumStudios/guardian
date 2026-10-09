package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.ItemOwnerCoordination
import com.bareminimumstudios.guardian.rollback.ItemReconciliationPort
import net.minecraft.server.MinecraftServer
import java.util.concurrent.CompletionStage

/** Internal actual-file adapter. Binding is not exclusion or evidence that old I/O has drained. */
internal class MinecraftBoundReconciliationPort(
    private val server: MinecraftServer,
    private val lease: ItemOwnerCoordination.Lease,
    private val session: MinecraftBoundInventories,
    private val quiescent: () -> Boolean
) : ItemReconciliationPort, AutoCloseable {
    private var closed=false
    override fun isExclusiveAndQuiescent(owners: Set<ItemSlotOwner>): Boolean {
        check(server.isSameThread)
        return !closed && owners==lease.owners && lease.isRetained(owners) && !lease.isCurrent(owners) && quiescent() && session.isCurrent()
    }
    override fun readLiveOwners(owners: Set<ItemSlotOwner>): InventorySnapshot? =
        if(isExclusiveAndQuiescent(owners))session.read() else null
    override fun readSavedOwner(owner: ItemSlotOwner): CompletionStage<InventorySnapshot?> {
        check(isExclusiveAndQuiescent(lease.owners) && owner in lease.owners)
        return session.readSaved(owner).thenApply<InventorySnapshot?> { it }
    }
    override fun close() {
        check(server.isSameThread)
        if(closed)return
        closed=true;session.close()
    }
}
