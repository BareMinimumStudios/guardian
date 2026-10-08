package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.ItemSlotOwner
import java.util.Collections
import java.util.UUID

/** Evidence from all persisted history, independent of the user's lookup filters. */
class ContainerHistoryGuard(
    newerOwners: Set<ItemSlotOwner> = emptySet(),
    changedBlocks: Set<ItemSlotOwner> = emptySet(),
    reservedOwners: Set<ItemSlotOwner> = emptySet(),
    claimedTransactions: Set<UUID> = emptySet()
) {
    val newerOwners: Set<ItemSlotOwner> = Collections.unmodifiableSet(HashSet(newerOwners))
    val changedBlocks: Set<ItemSlotOwner> = Collections.unmodifiableSet(HashSet(changedBlocks))
    val reservedOwners: Set<ItemSlotOwner> = Collections.unmodifiableSet(HashSet(reservedOwners))
    val claimedTransactions: Set<UUID> = Collections.unmodifiableSet(HashSet(claimedTransactions))
    val clear: Boolean get() = newerOwners.isEmpty() && changedBlocks.isEmpty() && reservedOwners.isEmpty() && claimedTransactions.isEmpty()
}
